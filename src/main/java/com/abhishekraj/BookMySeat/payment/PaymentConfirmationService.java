package com.abhishekraj.BookMySeat.payment;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.Payment;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.PaymentRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.service.BookingCancelledEvent;
import com.abhishekraj.BookMySeat.service.BookingService;
import com.abhishekraj.BookMySeat.service.PromoCodeService;
import com.abhishekraj.BookMySeat.service.SeatHoldService;
import com.abhishekraj.BookMySeat.support.AfterCommit;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The single place where "this order was paid" turns into a booking (or a refund). The client callback and the
 * Razorpay webhook both end up here; the payment row lock makes whichever arrives first win, and repeats are no-ops.
 */
@Slf4j
@Service
public class PaymentConfirmationService {

	private final PaymentRepository paymentRepository;
	private final BookingService bookingService;
	private final RefundService refundService;
	private final SeatHoldService seatHoldService;
	private final MeterRegistry meterRegistry;
	private final Clock clock;
	private final PromoCodeService promoCodeService;
	private final ShowRepository showRepository;

	public PaymentConfirmationService(PaymentRepository paymentRepository, BookingService bookingService,
			RefundService refundService, SeatHoldService seatHoldService, MeterRegistry meterRegistry, Clock clock,
			PromoCodeService promoCodeService, ShowRepository showRepository) {
		this.paymentRepository = paymentRepository;
		this.bookingService = bookingService;
		this.refundService = refundService;
		this.seatHoldService = seatHoldService;
		this.meterRegistry = meterRegistry;
		this.clock = clock;
		this.promoCodeService = promoCodeService;
		this.showRepository = showRepository;
		// At 0 from startup, so increase() counts the first one after a restart (see BookingMetrics)
		for (String outcome : new String[] {"confirmed", "refund", "expired", "attempt_failed"}) {
			meterRegistry.counter("payments", "outcome", outcome);
		}
	}

	@Transactional
	public Payment paymentSucceeded(String orderId, String paymentId, String source) {
		Instant now = Instant.now(clock);
		Payment payment = paymentRepository.findForUpdateByOrderId(orderId)
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found for order " + orderId));

		if (payment.getStatus() != PaymentStatus.PENDING && payment.getStatus() != PaymentStatus.EXPIRED) {
			if (!paymentId.equals(payment.getPaymentId())) {
				// A second, different payment on an order that is already settled: it must go back
				log.warn("Order {} already settled ({}); refunding extra payment {}", orderId, payment.getStatus(), paymentId);
				refundService.refundExtraPaymentAfterCommit(paymentId, payment.getAmount());
			}
			return payment; // already handled: repeat callback or duplicate webhook
		}

		// A late payment whose promo code use was given back must find room on the code again, or go back.
		// The show is locked first (as bookings and show cancellations do), then the code.
		if (payment.getStatus() == PaymentStatus.EXPIRED && payment.getPromoCode() != null) {
			showRepository.findForBookingById(payment.getShow().getId());
			if (!promoCodeService.retakeForLatePayment(payment)) {
				payment.requireRefund(paymentId, "The promo code was used up before the late payment arrived", now);
				count("refund");
				log.warn("Order {} paid late ({}) but its promo code is used up: refunding", orderId, source);
				refundService.refundAfterCommit(orderId);
				return payment;
			}
		}

		// Book only if every seat is still free and not held by anyone else. An EXPIRED payment can still succeed
		// late when nobody took the seats meanwhile; otherwise the customer gets the money back.
		Optional<Booking> booking = bookingService.tryConfirmPaid(payment.getUser().getId(), payment.getShow().getId(),
				payment.getSeatIds(), payment.getAmount(), payment.getPromoCode(), payment.getDiscountAmount());
		if (booking.isPresent()) {
			payment.confirm(paymentId, booking.get(), now);
			promoCodeService.paymentBooked(payment, booking.get());
			count("confirmed");
			log.info("Order {} paid ({}) and booked: booking {}", orderId, source, booking.get().getId());
		} else {
			promoCodeService.paymentReleased(payment);
			payment.requireRefund(paymentId, "Seats were no longer available when the payment arrived", now);
			count("refund");
			log.warn("Order {} paid ({}) but its seats are gone: refunding", orderId, source);
			refundService.refundAfterCommit(orderId);
		}
		return payment;
	}

	// Counted once the decision has committed
	private void count(String outcome) {
		AfterCommit.run(() -> meterRegistry.counter("payments", "outcome", outcome).increment());
	}

	/** The checkout window passed without a payment: release the seats. A payment can still arrive late. */
	@Transactional
	public void expireIfUnpaid(String orderId) {
		Instant now = Instant.now(clock);
		paymentRepository.findForUpdateByOrderId(orderId)
				.filter(payment -> payment.getStatus() == PaymentStatus.PENDING && payment.isExpired(now))
				.ifPresent(payment -> {
					payment.expire(now);
					promoCodeService.paymentReleased(payment);
					count("expired");
					Long userId = payment.getUser().getId();
					Long showId = payment.getShow().getId();
					AfterCommit.run(() -> seatHoldService.releaseAll(showId, payment.getSeatIds(), userId));
					log.info("Order {} expired unpaid; seats released", orderId);
				});
	}

	/** Cancelling a paid booking refunds it, in the same transaction as the cancellation. */
	@EventListener
	public void onBookingCancelled(BookingCancelledEvent event) {
		paymentRepository.findByBookingId(event.bookingId())
				.flatMap(payment -> paymentRepository.findForUpdateByOrderId(payment.getOrderId()))
				.filter(payment -> payment.getStatus() == PaymentStatus.CONFIRMED)
				.ifPresent(payment -> {
					payment.requireRefund(payment.getPaymentId(), "Booking cancelled", Instant.now(clock));
					count("refund");
					refundService.refundAfterCommit(payment.getOrderId());
				});
	}

	/** A failed attempt: noted for support, but the order stays open because the customer may retry. */
	@Transactional
	public void paymentFailed(String orderId, String reason) {
		paymentRepository.findForUpdateByOrderId(orderId)
				.filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
				.ifPresent(payment -> payment.recordFailure(reason, Instant.now(clock)));
		count("attempt_failed");
	}
}

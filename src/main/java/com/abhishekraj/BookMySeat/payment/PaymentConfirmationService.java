package com.abhishekraj.BookMySeat.payment;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.Payment;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.PaymentRepository;
import com.abhishekraj.BookMySeat.service.BookingService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
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
	private final MeterRegistry meterRegistry;
	private final Clock clock;

	public PaymentConfirmationService(PaymentRepository paymentRepository, BookingService bookingService,
			RefundService refundService, MeterRegistry meterRegistry, Clock clock) {
		this.paymentRepository = paymentRepository;
		this.bookingService = bookingService;
		this.refundService = refundService;
		this.meterRegistry = meterRegistry;
		this.clock = clock;
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

		// Book only if every seat is still free and not held by anyone else. An EXPIRED payment can still succeed
		// late when nobody took the seats meanwhile; otherwise the customer gets the money back.
		Optional<Booking> booking = bookingService.tryConfirmPaid(payment.getUser().getId(), payment.getShow().getId(),
				payment.getSeatIds(), payment.getAmount());
		if (booking.isPresent()) {
			payment.confirm(paymentId, booking.get(), now);
			meterRegistry.counter("payments", "outcome", "confirmed").increment();
			log.info("Order {} paid ({}) and booked: booking {}", orderId, source, booking.get().getId());
		} else {
			payment.requireRefund(paymentId, "Seats were no longer available when the payment arrived", now);
			meterRegistry.counter("payments", "outcome", "refund").increment();
			log.warn("Order {} paid ({}) but its seats are gone: refunding", orderId, source);
			refundService.refundAfterCommit(orderId);
		}
		return payment;
	}

	/** A failed attempt: noted for support, but the order stays open because the customer may retry. */
	@Transactional
	public void paymentFailed(String orderId, String reason) {
		paymentRepository.findForUpdateByOrderId(orderId)
				.filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
				.ifPresent(payment -> payment.recordFailure(reason, Instant.now(clock)));
		meterRegistry.counter("payments", "outcome", "attempt_failed").increment();
	}
}

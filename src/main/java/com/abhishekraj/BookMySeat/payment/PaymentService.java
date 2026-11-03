package com.abhishekraj.BookMySeat.payment;

import com.abhishekraj.BookMySeat.dto.CheckoutResponse;
import com.abhishekraj.BookMySeat.dto.ConfirmBookingRequest;
import com.abhishekraj.BookMySeat.dto.PaymentResponse;
import com.abhishekraj.BookMySeat.dto.VerifyPaymentRequest;
import com.abhishekraj.BookMySeat.entity.Payment;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatStatus;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.InvalidSignatureException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.PaymentRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.service.SeatHoldService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Online payment flow: checkout creates a Razorpay order and keeps the seats held for the payment window;
 * the booking is created only when the payment is reported as successful.
 */
@Slf4j
@Service
public class PaymentService {

	// Holds outlive the payment window slightly, so a payment finishing right at the deadline still finds them
	private static final Duration HOLD_GRACE = Duration.ofMinutes(1);

	private final PaymentRepository paymentRepository;
	private final ShowRepository showRepository;
	private final SeatRepository seatRepository;
	private final UserRepository userRepository;
	private final SeatHoldService seatHoldService;
	private final PaymentGateway gateway;
	private final PaymentConfirmationService confirmation;
	private final PaymentProperties properties;
	private final Clock clock;

	public PaymentService(PaymentRepository paymentRepository, ShowRepository showRepository,
			SeatRepository seatRepository, UserRepository userRepository, SeatHoldService seatHoldService,
			PaymentGateway gateway, PaymentConfirmationService confirmation, PaymentProperties properties, Clock clock) {
		this.paymentRepository = paymentRepository;
		this.showRepository = showRepository;
		this.seatRepository = seatRepository;
		this.userRepository = userRepository;
		this.seatHoldService = seatHoldService;
		this.gateway = gateway;
		this.confirmation = confirmation;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Starts paying for seats the user holds. Not one big transaction on purpose: the Razorpay call is
	 * network I/O and must not keep a database connection busy.
	 */
	public CheckoutResponse checkout(Long userId, ConfirmBookingRequest request) {
		requireEnabled();
		Instant now = Instant.now(clock);
		Show show = showRepository.findWithPricesById(request.showId())
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + request.showId()));
		if (show.isCancelled()) {
			throw new ConflictException("Show " + show.getId() + " has been cancelled");
		}
		if (!now.isBefore(show.getStartTime())) {
			throw new ConflictException("Show " + show.getId() + " has already started");
		}
		Set<Long> seatIds = Set.copyOf(request.seatIds());
		List<Seat> seats = seatRepository.findAllById(seatIds);
		if (seats.size() != seatIds.size() || !seats.stream().allMatch(seat -> seat.getShow().getId().equals(show.getId()))) {
			throw new ResourceNotFoundException("One or more seats were not found for show " + show.getId());
		}
		for (Seat seat : seats) {
			if (seat.getStatus() == SeatStatus.BOOKED) {
				throw new ConflictException("Seat " + seat.getSeatNumber() + " is already booked");
			}
		}

		// A retry (double click, page reload) gets the same open order back instead of a second one
		Payment open = paymentRepository
				.findFirstByUserIdAndShowIdAndStatusOrderByCreatedAtDesc(userId, show.getId(), PaymentStatus.PENDING)
				.filter(p -> p.getSeatIds().equals(seatIds) && !p.isExpired(now))
				.orElse(null);
		Instant expiresAt = open != null ? open.getExpiresAt() : now.plus(properties.window());
		if (!seatHoldService.extendAll(show.getId(), seatIds, userId, Duration.between(now, expiresAt).plus(HOLD_GRACE))) {
			throw new ConflictException("Hold these seats first: your hold has expired or they are held by someone else.");
		}
		if (open != null) {
			return toResponse(open);
		}

		BigDecimal amount = show.totalFor(seats); // computed here, never sent by the client
		PaymentGateway.Order order = gateway.createOrder(toMinor(amount), properties.currency(),
				"bms-" + userId + "-" + now.toEpochMilli());
		Payment payment = paymentRepository.save(new Payment(userRepository.getReferenceById(userId), show, seatIds,
				order.id(), amount, properties.currency(), now, expiresAt));
		log.info("Checkout: order {} for {} seat(s), {} {}", order.id(), seatIds.size(), amount, properties.currency());
		return toResponse(payment);
	}

	/** Razorpay Checkout's success callback, relayed by the client. Trusted only if the signature checks out. */
	public PaymentResponse verify(Long userId, VerifyPaymentRequest request) {
		requireEnabled();
		paymentRepository.findByOrderIdAndUserId(request.orderId(), userId)
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found for order " + request.orderId()));
		if (!PaymentSignatures.isValidPayment(request.orderId(), request.paymentId(), request.signature(),
				properties.razorpay().keySecret())) {
			throw new InvalidSignatureException("Payment signature is invalid");
		}
		return PaymentResponse.from(confirmation.paymentSucceeded(request.orderId(), request.paymentId(), "callback"));
	}

	@Transactional(readOnly = true)
	public PaymentResponse status(Long userId, String orderId) {
		return paymentRepository.findByOrderIdAndUserId(orderId, userId).map(PaymentResponse::from)
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found for order " + orderId));
	}

	private CheckoutResponse toResponse(Payment payment) {
		return new CheckoutResponse(payment.getOrderId(), properties.razorpay().keyId(), toMinor(payment.getAmount()),
				payment.getCurrency(), payment.getAmount(), payment.getShow().getId(),
				payment.getSeatIds().stream().sorted().collect(Collectors.toList()), payment.getExpiresAt());
	}

	private void requireEnabled() {
		if (!properties.enabled()) {
			throw new ConflictException("Online payment is not enabled on this server; confirm bookings directly.");
		}
	}

	/** Rupees to paise, exactly (BigDecimal, never double). */
	static long toMinor(BigDecimal amount) {
		return amount.movePointRight(2).longValueExact();
	}
}

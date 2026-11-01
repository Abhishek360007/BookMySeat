package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.BookingResponse;
import com.abhishekraj.BookMySeat.dto.CheckoutResponse;
import com.abhishekraj.BookMySeat.dto.ConfirmBookingRequest;
import com.abhishekraj.BookMySeat.dto.HoldRequest;
import com.abhishekraj.BookMySeat.dto.HoldResponse;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.payment.PaymentProperties;
import com.abhishekraj.BookMySeat.payment.PaymentService;
import com.abhishekraj.BookMySeat.service.BookingService;
import com.abhishekraj.BookMySeat.service.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@Tag(name = "Bookings", description = "Hold seats for 5 minutes, confirm them into a booking, cancel, and list your bookings.")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

	private final BookingService bookingService;
	private final IdempotencyService idempotencyService;
	private final PaymentService paymentService;
	private final PaymentProperties paymentProperties;

	@PostMapping("/hold")
	@ResponseStatus(HttpStatus.CREATED)
	public HoldResponse hold(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HoldRequest request) {
		return bookingService.holdSeats(userId(jwt), request);
	}

	@PostMapping("/hold/release")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void release(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HoldRequest request) {
		bookingService.releaseSeats(userId(jwt), request);
	}

	@Operation(summary = "Confirm held seats into a booking",
			description = "The total is computed on the server. Send an optional Idempotency-Key header to make retries safe: "
					+ "the same key with the same seats returns the original booking (header Idempotent-Replayed: true).")
	@PostMapping
	public ResponseEntity<BookingResponse> confirm(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ConfirmBookingRequest request) {
		if (paymentProperties.enabled()) {
			throw new ConflictException("Payment required: start with POST /api/bookings/checkout");
		}
		Long userId = userId(jwt);
		if (idempotencyKey == null) {
			return ResponseEntity.status(HttpStatus.CREATED).body(bookingService.confirm(userId, request));
		}

		String fingerprint = IdempotencyService.fingerprint(request.showId(), request.seatIds());
		Optional<Long> earlier = idempotencyService.begin(userId, idempotencyKey, fingerprint);
		if (earlier.isPresent()) {
			return ResponseEntity.status(HttpStatus.CREATED).header("Idempotent-Replayed", "true")
					.body(bookingService.getBooking(userId, earlier.get()));
		}

		BookingResponse booking;
		try {
			booking = bookingService.confirm(userId, request); // commits (or throws) before we record the result
		} catch (RuntimeException ex) {
			idempotencyService.release(userId, idempotencyKey, fingerprint);
			throw ex;
		}
		idempotencyService.complete(userId, idempotencyKey, fingerprint, booking.id());
		return ResponseEntity.status(HttpStatus.CREATED).body(booking);
	}

	@Operation(summary = "Start paying for held seats",
			description = "Creates a Razorpay order for the server-computed total and keeps the seats held for the "
					+ "payment window. Open Razorpay Checkout with the returned key and order id; the booking is "
					+ "created when the payment succeeds (POST /api/payments/verify or the Razorpay webhook).")
	@PostMapping("/checkout")
	@ResponseStatus(HttpStatus.CREATED)
	public CheckoutResponse checkout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConfirmBookingRequest request) {
		return paymentService.checkout(userId(jwt), request);
	}

	@PostMapping("/{id}/cancel")
	public BookingResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
		return bookingService.cancel(userId(jwt), id);
	}

	@GetMapping("/me")
	public List<BookingResponse> me(@AuthenticationPrincipal Jwt jwt) {
		return bookingService.myBookings(userId(jwt));
	}

	private Long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}
}

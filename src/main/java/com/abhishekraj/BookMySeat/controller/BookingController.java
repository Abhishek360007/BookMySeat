package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.BookingResponse;
import com.abhishekraj.BookMySeat.dto.ConfirmBookingRequest;
import com.abhishekraj.BookMySeat.dto.HoldRequest;
import com.abhishekraj.BookMySeat.dto.HoldResponse;
import com.abhishekraj.BookMySeat.service.BookingService;
import com.abhishekraj.BookMySeat.service.IdempotencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

	private final BookingService bookingService;
	private final IdempotencyService idempotencyService;

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

	// Optional Idempotency-Key: retrying with the same key returns the original booking instead of a second attempt
	@PostMapping
	public ResponseEntity<BookingResponse> confirm(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ConfirmBookingRequest request) {
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

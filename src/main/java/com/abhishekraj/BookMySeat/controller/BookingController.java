package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.BookingResponse;
import com.abhishekraj.BookMySeat.dto.ConfirmBookingRequest;
import com.abhishekraj.BookMySeat.dto.HoldRequest;
import com.abhishekraj.BookMySeat.dto.HoldResponse;
import com.abhishekraj.BookMySeat.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

	private final BookingService bookingService;

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

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public BookingResponse confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConfirmBookingRequest request) {
		return bookingService.confirm(userId(jwt), request);
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

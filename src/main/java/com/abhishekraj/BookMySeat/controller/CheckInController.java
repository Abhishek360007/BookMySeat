package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.CheckInRequest;
import com.abhishekraj.BookMySeat.dto.CheckInResponse;
import com.abhishekraj.BookMySeat.ticket.CheckInService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Secured as ADMIN-only in SecurityConfig ("/api/admin/**")
@Tag(name = "Tickets", description = "E-tickets for your confirmed bookings, and checking them in at the door (ADMIN).")
@RestController
@RequiredArgsConstructor
public class CheckInController {

	private final CheckInService checkInService;

	@Operation(summary = "Check a ticket in at the door (ADMIN)",
			description = "code: what the ticket's QR contains. 200 lets the holder in. 400 (reason INVALID) for a forged "
					+ "or unreadable code; 409 with reason ALREADY_CHECKED_IN (and checkedInAt), CANCELLED, OTHER_SHOW, "
					+ "TOO_EARLY or TOO_LATE otherwise. Check-in opens 6 hours before the show and closes 6 hours after "
					+ "it starts. A ticket is let in once, even if two scanners read it at the same moment.")
	@PostMapping("/api/admin/check-in")
	public CheckInResponse checkIn(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CheckInRequest request) {
		return checkInService.checkIn(request.showId(), request.code(), Long.parseLong(jwt.getSubject()));
	}
}

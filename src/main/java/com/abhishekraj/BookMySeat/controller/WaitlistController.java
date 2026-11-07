package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.WaitlistRequest;
import com.abhishekraj.BookMySeat.dto.WaitlistResponse;
import com.abhishekraj.BookMySeat.service.WaitlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Waitlist", description = "Wait in line for a sold-out show; when seats are freed, the next person in line gets them held.")
@RestController
@RequestMapping("/api/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

	private final WaitlistService waitlistService;

	@Operation(summary = "Join the waitlist of a sold-out show",
			description = "Only when every seat is booked (409 otherwise), once per show, for 1 to 6 seats together.")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public WaitlistResponse join(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WaitlistRequest request) {
		return waitlistService.join(userId(jwt), request);
	}

	@Operation(summary = "Leave the waitlist of a show")
	@DeleteMapping("/{showId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable Long showId) {
		waitlistService.leave(userId(jwt), showId);
	}

	@Operation(summary = "Your places in line", description = "Waiting (with your position) or with an offer of held seats.")
	@GetMapping("/me")
	public List<WaitlistResponse> mine(@AuthenticationPrincipal Jwt jwt) {
		return waitlistService.mine(userId(jwt));
	}

	private Long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}
}

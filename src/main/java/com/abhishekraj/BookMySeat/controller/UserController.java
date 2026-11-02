package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.UserResponse;
import com.abhishekraj.BookMySeat.service.AuthService;
import com.abhishekraj.BookMySeat.service.EmailVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users", description = "The logged-in user.")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

	private final AuthService authService;
	private final EmailVerificationService emailVerification;

	@GetMapping("/me")
	public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return authService.getCurrentUser(Long.parseLong(jwt.getSubject()));
	}

	@Operation(summary = "Send a new verification email",
			description = "Earlier links stop working. 409 if the email is already verified; rate-limited per IP.")
	@PostMapping("/me/verification-email")
	@ResponseStatus(HttpStatus.ACCEPTED) // queued; delivered by the email outbox
	public void resendVerificationEmail(@AuthenticationPrincipal Jwt jwt) {
		emailVerification.resend(Long.parseLong(jwt.getSubject()));
	}
}

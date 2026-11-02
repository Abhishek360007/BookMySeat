package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.LoginRequest;
import com.abhishekraj.BookMySeat.dto.LoginResponse;
import com.abhishekraj.BookMySeat.dto.RefreshRequest;
import com.abhishekraj.BookMySeat.dto.RegisterRequest;
import com.abhishekraj.BookMySeat.dto.UserResponse;
import com.abhishekraj.BookMySeat.dto.VerifyEmailRequest;
import com.abhishekraj.BookMySeat.dto.VerifyEmailResponse;
import com.abhishekraj.BookMySeat.service.AuthService;
import com.abhishekraj.BookMySeat.service.EmailVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "Register, log in, refresh, log out and verify an email. No token needed; login and registration are rate-limited per IP.")
@SecurityRequirements // public endpoints: no lock icon in Swagger
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final EmailVerificationService emailVerification;

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse register(@Valid @RequestBody RegisterRequest request) {
		return authService.register(request);
	}

	@PostMapping("/login")
	public LoginResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	// Exchanges a refresh token for a new access token and a new refresh token (the old one stops working)
	@PostMapping("/refresh")
	public LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return authService.refresh(request);
	}

	@Operation(summary = "Verify an email address",
			description = "The token comes from the link in the verification email sent after registration. "
					+ "Links work once and expire after 24 hours; opening an already used link again is not an error.")
	@PostMapping("/verify-email")
	public VerifyEmailResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
		return new VerifyEmailResponse(emailVerification.verify(request.token()), true);
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody RefreshRequest request) {
		authService.logout(request);
	}
}

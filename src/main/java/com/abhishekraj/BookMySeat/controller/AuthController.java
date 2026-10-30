package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.LoginRequest;
import com.abhishekraj.BookMySeat.dto.LoginResponse;
import com.abhishekraj.BookMySeat.dto.RefreshRequest;
import com.abhishekraj.BookMySeat.dto.RegisterRequest;
import com.abhishekraj.BookMySeat.dto.UserResponse;
import com.abhishekraj.BookMySeat.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

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

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody RefreshRequest request) {
		authService.logout(request);
	}
}

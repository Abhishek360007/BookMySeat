package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.LoginRequest;
import com.abhishekraj.BookMySeat.dto.LoginResponse;
import com.abhishekraj.BookMySeat.dto.RefreshRequest;
import com.abhishekraj.BookMySeat.dto.RegisterRequest;
import com.abhishekraj.BookMySeat.dto.UserResponse;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.entity.User;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.InvalidCredentialsException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.security.JwtService;
import com.abhishekraj.BookMySeat.security.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final RefreshTokenService refreshTokenService;

	@Transactional
	public UserResponse register(RegisterRequest request) {
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		if (userRepository.existsByEmail(email)) {
			throw new ConflictException("An account with this email already exists");
		}

		User user = new User();
		user.setName(request.name().trim());
		user.setEmail(email);
		user.setPassword(passwordEncoder.encode(request.password()));
		user.setRole(Role.USER);

		return UserResponse.from(userRepository.save(user));
	}

	@Transactional // not read-only: it stores a new refresh token
	public LoginResponse login(LoginRequest request) {
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		User user = userRepository.findByEmail(email)
				.orElseThrow(InvalidCredentialsException::new);

		if (!passwordEncoder.matches(request.password(), user.getPassword())) {
			throw new InvalidCredentialsException();
		}

		return tokens(user, refreshTokenService.issue(user));
	}

	// Deliberately not @Transactional: a reuse-detection revocation inside rotate() must commit even though it throws
	public LoginResponse refresh(RefreshRequest request) {
		RefreshTokenService.Rotated rotated = refreshTokenService.rotate(request.refreshToken());
		return tokens(rotated.user(), rotated.token());
	}

	public void logout(RefreshRequest request) {
		refreshTokenService.revokeSession(request.refreshToken());
	}

	private LoginResponse tokens(User user, String refreshToken) {
		return new LoginResponse(jwtService.generateToken(user), "Bearer", jwtService.getExpirationSeconds(),
				refreshToken, refreshTokenService.getLifetimeSeconds());
	}

	@Transactional(readOnly = true)
	public UserResponse getCurrentUser(Long userId) {
		return userRepository.findById(userId)
				.map(UserResponse::from)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
	}
}

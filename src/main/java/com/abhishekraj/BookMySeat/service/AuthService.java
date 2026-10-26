package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.LoginRequest;
import com.abhishekraj.BookMySeat.dto.LoginResponse;
import com.abhishekraj.BookMySeat.dto.RegisterRequest;
import com.abhishekraj.BookMySeat.dto.UserResponse;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.entity.User;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.InvalidCredentialsException;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.security.JwtService;
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

	@Transactional(readOnly = true)
	public LoginResponse login(LoginRequest request) {
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		User user = userRepository.findByEmail(email)
				.orElseThrow(InvalidCredentialsException::new);

		if (!passwordEncoder.matches(request.password(), user.getPassword())) {
			throw new InvalidCredentialsException();
		}

		String token = jwtService.generateToken(user);
		return new LoginResponse(token, "Bearer", jwtService.getExpirationSeconds());
	}
}

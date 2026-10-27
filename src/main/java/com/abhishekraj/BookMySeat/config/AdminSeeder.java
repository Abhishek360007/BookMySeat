package com.abhishekraj.BookMySeat.config;

import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.entity.User;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements ApplicationRunner {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	@Value("${app.admin.email:}")
	private String adminEmail;

	@Value("${app.admin.password:}")
	private String adminPassword;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (adminEmail.isBlank() || adminPassword.isBlank()) {
			log.info("ADMIN_EMAIL / ADMIN_PASSWORD not set, skipping admin creation");
			return;
		}
		if (adminPassword.length() < 12) {
			throw new IllegalStateException("ADMIN_PASSWORD must be at least 12 characters");
		}

		String email = adminEmail.trim().toLowerCase(Locale.ROOT);
		if (userRepository.existsByEmail(email)) {
			log.info("Admin account {} already exists", email);
			return;
		}

		User admin = new User();
		admin.setName("Admin");
		admin.setEmail(email);
		admin.setPassword(passwordEncoder.encode(adminPassword));
		admin.setRole(Role.ADMIN);
		userRepository.save(admin);
		log.info("Created admin account {}", email);
	}
}

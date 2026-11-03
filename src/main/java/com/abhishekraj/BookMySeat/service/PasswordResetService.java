package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.entity.PasswordResetToken;
import com.abhishekraj.BookMySeat.entity.User;
import com.abhishekraj.BookMySeat.exception.InvalidResetLinkException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.notification.EmailOutbox;
import com.abhishekraj.BookMySeat.notification.EmailTemplates;
import com.abhishekraj.BookMySeat.repository.PasswordResetTokenRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.security.RefreshTokenService;
import com.abhishekraj.BookMySeat.security.Tokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * "Forgot password": an emailed single-use link that sets a new password and logs out every session.
 * The request never reveals whether an account exists, so it can't be used to discover who has one.
 */
@Slf4j
@Service
public class PasswordResetService {

	private final PasswordResetTokenRepository tokenRepository;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokenService;
	private final EmailOutbox outbox;
	private final EmailTemplates templates;
	private final Clock clock;
	private final Duration linkLifetime;
	private final String demoEmail;

	public PasswordResetService(PasswordResetTokenRepository tokenRepository, UserRepository userRepository,
			PasswordEncoder passwordEncoder, RefreshTokenService refreshTokenService, EmailOutbox outbox,
			EmailTemplates templates, Clock clock,
			@Value("${auth.password-reset.link-lifetime}") Duration linkLifetime,
			@Value("${app.demo.email:}") String demoEmail) {
		this.tokenRepository = tokenRepository;
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokenService = refreshTokenService;
		this.outbox = outbox;
		this.templates = templates;
		this.clock = clock;
		this.linkLifetime = linkLifetime;
		this.demoEmail = demoEmail.trim().toLowerCase(Locale.ROOT);
	}

	/** Emails a reset link if the account exists. The caller always answers the same way either way. */
	@Transactional
	public void requestReset(String email) {
		Optional<User> found = userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT))
				.filter(user -> !isDemo(user));
		if (found.isEmpty()) {
			log.info("Password reset requested for an unknown or protected address");
			return;
		}
		User user = found.get();
		tokenRepository.deleteUnusedByUserId(user.getId());
		String token = Tokens.newToken();
		Instant now = Instant.now(clock);
		tokenRepository.save(new PasswordResetToken(user, Tokens.sha256Hex(token), now, now.plus(linkLifetime)));
		outbox.enqueue("PASSWORD_RESET", templates.passwordReset(user, token, linkLifetime));
	}

	@Transactional
	public void resetPassword(String token, String newPassword) {
		PasswordResetToken link = tokenRepository.findByTokenHash(Tokens.sha256Hex(token))
				.orElseThrow(InvalidResetLinkException::new);
		Instant now = Instant.now(clock);
		if (!link.isUsable(now)) {
			throw new InvalidResetLinkException();
		}
		User user = link.getUser();
		link.use(now);
		tokenRepository.deleteUnusedByUserId(user.getId());
		changePassword(user, newPassword, now);
		// Opening the link proved the user reads this inbox
		if (!user.isEmailVerified()) {
			user.setEmailVerifiedAt(now);
		}
		log.info("Password reset for user {}", user.getId());
	}

	/** Sets the password, logs out every session and tells the user. Runs in the caller's transaction. */
	void changePassword(User user, String newPassword, Instant now) {
		user.setPassword(passwordEncoder.encode(newPassword));
		int revoked = refreshTokenService.revokeAllSessions(user.getId());
		outbox.enqueue("PASSWORD_CHANGED", templates.passwordChanged(user, now));
		log.info("Password changed for user {}; {} session token(s) revoked", user.getId(), revoked);
	}

	/**
	 * Logged-in change: needs the current password (a stolen access token alone isn't enough). Logs out every
	 * session, including this one; the caller hands this device a fresh session.
	 */
	@Transactional
	public User changePassword(Long userId, String currentPassword, String newPassword) {
		User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
		if (isDemo(user)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The demo account's password can't be changed");
		}
		if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
			// 400, not 401: the access token is fine, only the typed password is wrong
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Your current password is incorrect");
		}
		changePassword(user, newPassword, Instant.now(clock));
		return user;
	}

	boolean isDemo(User user) {
		return !demoEmail.isEmpty() && demoEmail.equals(user.getEmail());
	}
}

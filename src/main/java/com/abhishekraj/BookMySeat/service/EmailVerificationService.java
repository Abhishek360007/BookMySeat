package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.entity.EmailVerificationToken;
import com.abhishekraj.BookMySeat.entity.User;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.InvalidVerificationLinkException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.notification.EmailOutbox;
import com.abhishekraj.BookMySeat.notification.EmailTemplates;
import com.abhishekraj.BookMySeat.repository.EmailVerificationTokenRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.security.Tokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Email verification by single-use link. Optional: an unverified account can still book; the web app
 * just reminds the user. The link carries a random token; only its hash is stored.
 */
@Slf4j
@Service
public class EmailVerificationService {

	private final EmailVerificationTokenRepository tokenRepository;
	private final UserRepository userRepository;
	private final EmailOutbox outbox;
	private final EmailTemplates templates;
	private final Clock clock;
	private final Duration linkLifetime;
	private final String demoEmail;

	public EmailVerificationService(EmailVerificationTokenRepository tokenRepository, UserRepository userRepository,
			EmailOutbox outbox, EmailTemplates templates, Clock clock,
			@Value("${auth.email-verification.link-lifetime}") Duration linkLifetime,
			@Value("${app.demo.email:}") String demoEmail) {
		this.tokenRepository = tokenRepository;
		this.userRepository = userRepository;
		this.outbox = outbox;
		this.templates = templates;
		this.clock = clock;
		this.linkLifetime = linkLifetime;
		this.demoEmail = demoEmail.trim().toLowerCase(Locale.ROOT);
	}

	/** Creates a link and queues the email, in the caller's transaction (registration commits with its email). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void sendLink(User user) {
		if (!demoEmail.isEmpty() && demoEmail.equals(user.getEmail())) {
			// The public demo account's inbox belongs to nobody (no email is ever sent to it): no reminder banner
			user.setEmailVerifiedAt(Instant.now(clock));
			return;
		}
		tokenRepository.deleteUnusedByUserId(user.getId());
		String token = Tokens.newToken();
		Instant now = Instant.now(clock);
		tokenRepository.save(new EmailVerificationToken(user, Tokens.sha256Hex(token), now, now.plus(linkLifetime)));
		outbox.enqueue("VERIFY_EMAIL", templates.verifyEmail(user, token, linkLifetime));
	}

	@Transactional
	public void resend(Long userId) {
		User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
		if (user.isEmailVerified()) {
			throw new ConflictException("Your email is already verified");
		}
		sendLink(user);
	}

	/** Returns the verified email. Opening a used link again is fine as long as it verified this account. */
	@Transactional
	public String verify(String token) {
		EmailVerificationToken link = tokenRepository.findByTokenHash(Tokens.sha256Hex(token))
				.orElseThrow(InvalidVerificationLinkException::new);
		User user = link.getUser();
		Instant now = Instant.now(clock);
		if (link.getUsedAt() != null && user.isEmailVerified()) {
			return user.getEmail();
		}
		if (!link.isUsable(now)) {
			throw new InvalidVerificationLinkException();
		}
		link.use(now);
		user.setEmailVerifiedAt(now);
		log.info("Email verified for user {}", user.getId());
		return user.getEmail();
	}
}

package com.abhishekraj.BookMySeat.security;

import com.abhishekraj.BookMySeat.entity.RefreshToken;
import com.abhishekraj.BookMySeat.entity.User;
import com.abhishekraj.BookMySeat.exception.InvalidRefreshTokenException;
import com.abhishekraj.BookMySeat.repository.RefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// Opaque, rotating refresh tokens. The client gets a random 256-bit token; only its SHA-256 hash is stored.
@Slf4j
@Service
public class RefreshTokenService {

	private final RefreshTokenRepository repository;
	private final Clock clock;
	private final Duration lifetime;

	public RefreshTokenService(RefreshTokenRepository repository, Clock clock,
			@Value("${jwt.refresh-expiration-days}") long lifetimeDays) {
		this.repository = repository;
		this.clock = clock;
		this.lifetime = Duration.ofDays(lifetimeDays);
	}

	public record Rotated(User user, String token) {}

	/** Starts a new token family, i.e. a new login session. */
	@Transactional
	public String issue(User user) {
		return save(user, UUID.randomUUID());
	}

	/**
	 * Swaps a valid token for a new one in the same family. A token that was already used means it was
	 * probably stolen, so the whole family is revoked: both the thief and the real user must log in again.
	 * noRollbackFor keeps that revocation even though the request fails.
	 */
	@Transactional(noRollbackFor = InvalidRefreshTokenException.class)
	public Rotated rotate(String rawToken) {
		Instant now = Instant.now(clock);
		RefreshToken token = repository.findByTokenHash(hash(rawToken))
				.orElseThrow(InvalidRefreshTokenException::new);

		if (token.isRevoked()) {
			int revoked = repository.revokeFamily(token.getFamilyId(), now);
			log.warn("Refresh token reuse detected for user {}; revoked {} token(s) in its family",
					token.getUser().getId(), revoked);
			throw new InvalidRefreshTokenException();
		}
		if (token.isExpired(now)) {
			throw new InvalidRefreshTokenException();
		}

		token.revoke(now);
		User user = (User) Hibernate.unproxy(token.getUser()); // the caller builds the JWT outside this transaction
		return new Rotated(user, save(user, token.getFamilyId()));
	}

	/** Logout: ends the session this token belongs to. Unknown tokens are ignored, so logout is idempotent. */
	@Transactional
	public void revokeSession(String rawToken) {
		repository.findByTokenHash(hash(rawToken))
				.ifPresent(token -> repository.revokeFamily(token.getFamilyId(), Instant.now(clock)));
	}

	/** Logs the user out everywhere: no refresh token of theirs works any more. */
	@Transactional
	public int revokeAllSessions(Long userId) {
		return repository.revokeAllForUser(userId, Instant.now(clock));
	}

	public long getLifetimeSeconds() {
		return lifetime.toSeconds();
	}

	private String save(User user, UUID familyId) {
		String raw = Tokens.newToken();
		Instant now = Instant.now(clock);
		repository.save(new RefreshToken(user, hash(raw), familyId, now, now.plus(lifetime)));
		return raw;
	}

	private static String hash(String raw) {
		return Tokens.sha256Hex(raw);
	}
}

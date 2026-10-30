package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.IdempotencyKeyReusedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Idempotency-Key support for confirming bookings, so a client can safely retry after a timeout.
 * Redis value per (user, key): "pending:{fingerprint}" while running, then "done:{fingerprint}:{bookingId}".
 */
@Service
public class IdempotencyService {

	private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]{1,100}");
	// Short, so a crash mid-request frees the key quickly; the final result is kept for a day
	private static final Duration PENDING_TTL = Duration.ofSeconds(60);
	private static final Duration DONE_TTL = Duration.ofHours(24);

	// Delete the key only while it is still this request's pending marker
	private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
			if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	public IdempotencyService(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** What the request is about: the same key must always mean the same show and seats. */
	public static String fingerprint(Long showId, Collection<Long> seatIds) {
		return showId + ":" + seatIds.stream().distinct().sorted().map(String::valueOf).collect(Collectors.joining(","));
	}

	/**
	 * Claims the key. Returns the booking id of an earlier successful request with the same key and
	 * fingerprint (a replay), or empty if this request should go ahead.
	 */
	public Optional<Long> begin(Long userId, String idempotencyKey, String fingerprint) {
		if (!VALID_KEY.matcher(idempotencyKey).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Idempotency-Key must be 1-100 characters: letters, digits, '-' or '_'");
		}
		String key = redisKey(userId, idempotencyKey);
		if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "pending:" + fingerprint, PENDING_TTL))) {
			return Optional.empty();
		}

		String existing = redis.opsForValue().get(key);
		if (existing == null || existing.startsWith("pending:")) {
			throw new ConflictException("A request with this Idempotency-Key is still being processed. Retry shortly.");
		}
		// "done:{fingerprint}:{bookingId}"; the fingerprint itself contains ':', so the id is after the last one
		int lastColon = existing.lastIndexOf(':');
		String storedFingerprint = existing.substring("done:".length(), lastColon);
		if (!storedFingerprint.equals(fingerprint)) {
			throw new IdempotencyKeyReusedException();
		}
		return Optional.of(Long.parseLong(existing.substring(lastColon + 1)));
	}

	public void complete(Long userId, String idempotencyKey, String fingerprint, Long bookingId) {
		redis.opsForValue().set(redisKey(userId, idempotencyKey), "done:" + fingerprint + ":" + bookingId, DONE_TTL);
	}

	/** The request failed: free the key so the client can retry with it. */
	public void release(Long userId, String idempotencyKey, String fingerprint) {
		redis.execute(RELEASE_SCRIPT, List.of(redisKey(userId, idempotencyKey)), "pending:" + fingerprint);
	}

	private static String redisKey(Long userId, String idempotencyKey) {
		return "idem:booking:" + userId + ":" + idempotencyKey;
	}
}

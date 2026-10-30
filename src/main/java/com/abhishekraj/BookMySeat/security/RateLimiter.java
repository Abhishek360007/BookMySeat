package com.abhishekraj.BookMySeat.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

// Fixed-window counter in Redis, shared by every app instance
@Component
public class RateLimiter {

	// INCR and set the expiry on the first hit in one atomic step; returns {count, secondsLeft}
	@SuppressWarnings("rawtypes")
	private static final RedisScript<List> HIT_SCRIPT = new DefaultRedisScript<>("""
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
			return {count, redis.call('TTL', KEYS[1])}
			""", List.class);

	private final StringRedisTemplate redis;

	public RateLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public record Result(boolean allowed, long retryAfterSeconds) {}

	public Result hit(String key, int maxRequests, Duration window) {
		List<?> reply = redis.execute(HIT_SCRIPT, List.of(key), String.valueOf(window.toSeconds()));
		long count = ((Number) reply.get(0)).longValue();
		long ttl = ((Number) reply.get(1)).longValue();
		return new Result(count <= maxRequests, Math.max(ttl, 1));
	}
}

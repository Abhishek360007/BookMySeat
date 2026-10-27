package com.abhishekraj.BookMySeat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class SeatHoldService {

	// All-or-nothing in one atomic step: fail if ANY seat is held by someone else,
	// otherwise (re)set every key, which also refreshes the TTL of seats this user already holds
	private static final RedisScript<Long> HOLD_SCRIPT = new DefaultRedisScript<>("""
			for i, key in ipairs(KEYS) do
				local owner = redis.call('GET', key)
				if owner and owner ~= ARGV[1] then return 0 end
			end
			for i, key in ipairs(KEYS) do
				redis.call('SET', key, ARGV[1], 'EX', ARGV[2])
			end
			return 1
			""", Long.class);

	// Delete the key ONLY if it still belongs to this user (atomic check-and-delete)
	private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
			if redis.call('get', KEYS[1]) == ARGV[1] then
				return redis.call('del', KEYS[1])
			else
				return 0
			end
			""", Long.class);

	private final StringRedisTemplate redis;
	private final Duration holdDuration;

	public SeatHoldService(StringRedisTemplate redis,
			@Value("${booking.hold-minutes}") long holdMinutes) {
		this.redis = redis;
		this.holdDuration = Duration.ofMinutes(holdMinutes);
	}

	private String key(Long showId, Long seatId) {
		return "hold:show:" + showId + ":seat:" + seatId;
	}

	/** All-or-nothing: either every seat is held for this user, or none are. */
	public boolean holdAll(Long showId, List<Long> seatIds, Long userId) {
		List<String> keys = seatIds.stream().map(id -> key(showId, id)).toList();
		Long ok = redis.execute(HOLD_SCRIPT, keys,
				String.valueOf(userId), String.valueOf(holdDuration.toSeconds()));
		return ok != null && ok == 1;
	}

	public void releaseAll(Long showId, Collection<Long> seatIds, Long userId) {
		String owner = String.valueOf(userId);
		for (Long seatId : seatIds) {
			redis.execute(RELEASE_SCRIPT, List.of(key(showId, seatId)), owner);
		}
	}

	/** True only if EVERY one of these seats is currently held by this user. */
	public boolean isHeldBy(Long showId, Collection<Long> seatIds, Long userId) {
		List<String> keys = seatIds.stream().map(id -> key(showId, id)).toList();
		List<String> owners = redis.opsForValue().multiGet(keys);
		String me = String.valueOf(userId);
		return owners != null && owners.stream().allMatch(me::equals);
	}

	/** Returns which of these seats are currently held by anyone, in ONE Redis call. */
	public Set<Long> findHeldSeatIds(Long showId, List<Long> seatIds) {
		if (seatIds.isEmpty()) {
			return Set.of();
		}
		List<String> keys = seatIds.stream().map(id -> key(showId, id)).toList();
		List<String> owners = redis.opsForValue().multiGet(keys);

		Set<Long> held = new HashSet<>();
		for (int i = 0; i < seatIds.size(); i++) {
			if (owners != null && owners.get(i) != null) {
				held.add(seatIds.get(i));
			}
		}
		return held;
	}

	public Instant newExpiryTime() {
		return Instant.now().plus(holdDuration);
	}
}

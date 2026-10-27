package com.abhishekraj.BookMySeat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class SeatHoldService {

	// Delete the key ONLY if it still belongs to this user (atomic check-and-delete)
	private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
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
		String owner = String.valueOf(userId);
		List<Long> acquired = new ArrayList<>();

		for (Long seatId : seatIds) {
			Boolean ok = redis.opsForValue().setIfAbsent(key(showId, seatId), owner, holdDuration);
			if (Boolean.TRUE.equals(ok)) {
				acquired.add(seatId);
			} else {
				releaseAll(showId, acquired, userId); // undo the seats we already grabbed
				return false;
			}
		}
		return true;
	}

	public void releaseAll(Long showId, List<Long> seatIds, Long userId) {
		String owner = String.valueOf(userId);
		for (Long seatId : seatIds) {
			redis.execute(RELEASE_SCRIPT, List.of(key(showId, seatId)), owner);
		}
	}

	public boolean isHeldBy(Long showId, Long seatId, Long userId) {
		return String.valueOf(userId).equals(redis.opsForValue().get(key(showId, seatId)));
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

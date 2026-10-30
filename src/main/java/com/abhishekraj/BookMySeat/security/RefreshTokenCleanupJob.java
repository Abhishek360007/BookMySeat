package com.abhishekraj.BookMySeat.security;

import com.abhishekraj.BookMySeat.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

// Keeps the refresh_tokens table from growing forever
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

	private final RefreshTokenRepository repository;
	private final Clock clock;

	// lockAtLeastFor: even a fast run holds the lock for a minute, so instances that wake up together don't all run it
	@Scheduled(initialDelayString = "${cleanup.refresh-tokens.initial-delay}",
			fixedDelayString = "${cleanup.refresh-tokens.interval}")
	@SchedulerLock(name = "refreshTokenCleanup", lockAtLeastFor = "PT1M")
	@Transactional
	public void deleteExpiredTokens() {
		int deleted = repository.deleteExpired(Instant.now(clock));
		log.info("Refresh token cleanup: deleted {} expired token(s)", deleted);
	}
}

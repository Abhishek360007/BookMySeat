package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.RefreshToken;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.entity.User;
import com.abhishekraj.BookMySeat.repository.RefreshTokenRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.security.RefreshTokenCleanupJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Same configuration as BookingApiTest, so it reuses that cached context
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@Import(TestcontainersConfig.class)
class RefreshTokenCleanupJobTest {

	@Autowired RefreshTokenCleanupJob job;
	@Autowired RefreshTokenRepository tokens;
	@Autowired UserRepository users;
	@Autowired JdbcTemplate jdbc;

	private User user;

	@BeforeEach
	void setUp() {
		// Each test starts with the job unlocked. Expire the lock rather than deleting the row: ShedLock
		// remembers that the row exists and only UPDATEs it afterwards, so a deleted row would look locked forever.
		jdbc.update("update shedlock set lock_until = timestamp '2000-01-01 00:00:00'");
		user = new User();
		user.setName("Cleanup Test");
		user.setEmail("cleanup-user@example.com");
		user.setPassword("not-a-real-hash");
		user.setRole(Role.USER);
		user = users.save(user);
	}

	@AfterEach
	void cleanUp() {
		users.delete(user); // its tokens go too (ON DELETE CASCADE)
	}

	@Test
	void deletesExpiredTokens_keepsActiveAndRevokedButUnexpiredOnes() {
		Instant now = Instant.now();
		token(now.minus(1, ChronoUnit.DAYS), false);                 // expired: deleted
		RefreshToken active = token(now.plus(7, ChronoUnit.DAYS), false);
		RefreshToken revoked = token(now.plus(3, ChronoUnit.DAYS), true); // kept for reuse detection

		job.deleteExpiredTokens();

		assertThat(tokens.findAll()).extracting(RefreshToken::getId)
				.containsExactlyInAnyOrder(active.getId(), revoked.getId());
	}

	@Test
	void secondRunWithinTheLockWindow_isSkipped() {
		job.deleteExpiredTokens(); // takes the lock for at least a minute
		RefreshToken expired = token(Instant.now().minus(1, ChronoUnit.DAYS), false);

		job.deleteExpiredTokens(); // another "instance" fires right away: ShedLock skips it

		assertThat(tokens.findById(expired.getId())).isPresent();
		assertThat(jdbc.queryForObject("select count(*) from shedlock where name = 'refreshTokenCleanup'", Integer.class))
				.isEqualTo(1);
	}

	private RefreshToken token(Instant expiresAt, boolean revoked) {
		RefreshToken token = new RefreshToken(user, UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000",
				UUID.randomUUID(), expiresAt.minus(7, ChronoUnit.DAYS), expiresAt);
		if (revoked) {
			token.revoke(Instant.now());
		}
		return tokens.save(token);
	}
}

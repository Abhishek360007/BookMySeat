package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	// Row lock: two concurrent refreshes with the same token cannot both succeed
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<RefreshToken> findByTokenHash(String tokenHash);

	@Modifying
	@Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
	int revokeFamily(UUID familyId, Instant now);

	// Only expired tokens: revoked-but-unexpired ones must stay so a replayed stolen token is still recognised
	@Modifying
	@Query("delete from RefreshToken t where t.expiresAt < :now")
	int deleteExpired(Instant now);
}

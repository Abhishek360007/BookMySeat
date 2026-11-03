package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

	// Row lock: the same link opened twice at once can't reset the password twice
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<PasswordResetToken> findByTokenHash(String tokenHash);

	// A new link (or a completed reset) makes the older ones useless
	@Modifying
	@Query("DELETE FROM PasswordResetToken t WHERE t.user.id = :userId AND t.usedAt IS NULL")
	int deleteUnusedByUserId(Long userId);
}

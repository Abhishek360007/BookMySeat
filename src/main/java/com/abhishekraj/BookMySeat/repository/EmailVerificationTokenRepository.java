package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

	Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

	// A new link replaces the old ones
	@Modifying
	@Query("DELETE FROM EmailVerificationToken t WHERE t.user.id = :userId AND t.usedAt IS NULL")
	int deleteUnusedByUserId(Long userId);
}

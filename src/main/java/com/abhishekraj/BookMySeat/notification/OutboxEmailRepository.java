package com.abhishekraj.BookMySeat.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxEmailRepository extends JpaRepository<OutboxEmail, Long> {

	@Query(value = """
			SELECT id FROM email_outbox WHERE status = 'PENDING' AND next_attempt_at <= :now
			ORDER BY next_attempt_at LIMIT :limit""", nativeQuery = true)
	List<Long> findDueIds(Instant now, int limit);

	// SKIP LOCKED: if another sender is already delivering this email, skip it instead of waiting and sending it twice
	@Query(value = "SELECT * FROM email_outbox WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
	Optional<OutboxEmail> lockIfFree(Long id);

	@Query("""
			SELECT new com.abhishekraj.BookMySeat.notification.OutboxBacklog(
				COALESCE(SUM(CASE WHEN e.status = com.abhishekraj.BookMySeat.notification.OutboxEmail.Status.PENDING THEN 1 ELSE 0 END), 0),
				MIN(CASE WHEN e.status = com.abhishekraj.BookMySeat.notification.OutboxEmail.Status.PENDING THEN e.createdAt END),
				COALESCE(SUM(CASE WHEN e.status = com.abhishekraj.BookMySeat.notification.OutboxEmail.Status.FAILED THEN 1 ELSE 0 END), 0))
			FROM OutboxEmail e""")
	OutboxBacklog backlog();

	@Modifying
	@Query("DELETE FROM OutboxEmail e WHERE e.status = com.abhishekraj.BookMySeat.notification.OutboxEmail.Status.SENT AND e.sentAt < :before")
	int deleteSentBefore(Instant before);
}

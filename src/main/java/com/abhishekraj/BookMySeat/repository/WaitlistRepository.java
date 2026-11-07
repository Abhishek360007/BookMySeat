package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.WaitlistEntry;
import com.abhishekraj.BookMySeat.entity.WaitlistStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

	Optional<WaitlistEntry> findByShowIdAndUserIdAndStatusIn(Long showId, Long userId, Collection<WaitlistStatus> statuses);

	@EntityGraph(attributePaths = {"show", "show.venue", "offeredSeats"})
	List<WaitlistEntry> findByUserIdAndStatusInOrderByIdAsc(Long userId, Collection<WaitlistStatus> statuses);

	// Place in line: how many still wait ahead of this entry (+1 = its position)
	@Query("""
			select count(e) from WaitlistEntry e where e.show.id = :showId and e.id < :entryId
			  and e.status = com.abhishekraj.BookMySeat.entity.WaitlistStatus.WAITING""")
	long countWaitingAhead(Long showId, Long entryId);

	// One offer round per show at a time (a transaction-scoped Postgres advisory lock, released at commit): two
	// cancellations at once offer one after the other, so the second sees whom the first offered to. Not the show's
	// row lock, which a show cancellation takes before its bookings (a customer cancel would take them the other way).
	@Query(value = "select 1 from (select pg_advisory_xact_lock(21, cast(:showId as integer))) as locked", nativeQuery = true)
	Integer lockOffersOf(Long showId);

	List<WaitlistEntry> findByShowIdAndStatusOrderByIdAsc(Long showId, WaitlistStatus status);

	List<WaitlistEntry> findByShowIdAndStatusIn(Long showId, Collection<WaitlistStatus> statuses);

	List<WaitlistEntry> findTop50ByStatusAndOfferExpiresAtBefore(WaitlistStatus status, Instant now);

	// Active entries of shows that were cancelled or have started: their line is over
	@Query("""
			select e from WaitlistEntry e where e.status in :statuses
			  and (e.show.status = com.abhishekraj.BookMySeat.entity.ShowStatus.CANCELLED or e.show.startTime <= :now)""")
	List<WaitlistEntry> findActiveOfEndedShows(Collection<WaitlistStatus> statuses, Instant now);

	// Shows with people waiting (the sweep offers them any seat that is free and not held)
	@Query("""
			select distinct e.show.id from WaitlistEntry e
			where e.status = com.abhishekraj.BookMySeat.entity.WaitlistStatus.WAITING
			  and e.show.status = com.abhishekraj.BookMySeat.entity.ShowStatus.SCHEDULED and e.show.startTime > :now""")
	List<Long> findShowIdsWithWaiting(Instant now);
}

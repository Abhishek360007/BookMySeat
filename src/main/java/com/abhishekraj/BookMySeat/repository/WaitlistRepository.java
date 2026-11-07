package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.WaitlistEntry;
import com.abhishekraj.BookMySeat.entity.WaitlistStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

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

	// The line of a show, in order, FOR UPDATE SKIP LOCKED (timeout -2): two cancellations at once each offer
	// to different people, instead of both offering to the first one (or one waiting for the other)
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
	@Query("""
			select e from WaitlistEntry e where e.show.id = :showId
			  and e.status = com.abhishekraj.BookMySeat.entity.WaitlistStatus.WAITING order by e.id""")
	List<WaitlistEntry> lockWaitingLine(Long showId);

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

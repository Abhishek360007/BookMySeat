package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.WaitlistEntry;
import com.abhishekraj.BookMySeat.entity.WaitlistStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}

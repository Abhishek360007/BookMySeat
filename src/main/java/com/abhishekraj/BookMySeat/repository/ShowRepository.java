package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.ShowStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ShowRepository extends JpaRepository<Show, Long>, JpaSpecificationExecutor<Show> {

	@Override
	@EntityGraph(attributePaths = "venue")
	List<Show> findAll();

	@EntityGraph(attributePaths = "venue")
	List<Show> findByVenueId(Long venueId);

	// Search: the venue is a to-one, so fetching it with paging is safe (one query plus a count)
	@Override
	@EntityGraph(attributePaths = "venue")
	Page<Show> findAll(Specification<Show> spec, Pageable pageable);

	boolean existsByVenueId(Long venueId);

	long countByStatusAndStartTimeAfter(ShowStatus status, Instant now);

	// With its category prices: for pricing outside a transaction (checkout)
	@EntityGraph(attributePaths = "categoryPrices")
	Optional<Show> findWithPricesById(Long id);

	// FOR SHARE: many bookings can confirm at once, but a show can't be cancelled in the middle of one
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select s from Show s where s.id = :id")
	Optional<Show> findForBookingById(Long id);

	// Atomic, so two reviews saved at once both count. The CHECK on shows refuses a count that would go wrong.
	@Modifying
	@Query(value = "UPDATE shows SET rating_sum = rating_sum + :sumDelta, rating_count = rating_count + :countDelta WHERE id = :showId",
			nativeQuery = true)
	int adjustRating(Long showId, int sumDelta, int countDelta);

	// FOR UPDATE: waits for confirms in progress to commit, and blocks new ones until the cancel commits
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Show s where s.id = :id")
	Optional<Show> findForUpdateById(Long id);
}

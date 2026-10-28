package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	@EntityGraph(attributePaths = {"show", "show.venue", "seats"})
	List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);

	@EntityGraph(attributePaths = {"show", "show.venue", "seats"})
	Optional<Booking> findByIdAndUserId(Long id, Long userId);

	// Only the to-one user is fetched: fetch-joining the seats collection while paging
	// would make Hibernate load every row and page in memory (HHH90003004)
	@EntityGraph(attributePaths = {"user"})
	Page<Booking> findByShowIdAndStatus(Long showId, BookingStatus status, Pageable pageable);

	@Query("select coalesce(sum(b.totalAmount), 0) from Booking b where b.show.id = :showId and b.status = :status")
	BigDecimal sumTotal(Long showId, BookingStatus status);

	long countByShowIdAndStatus(Long showId, BookingStatus status);
}

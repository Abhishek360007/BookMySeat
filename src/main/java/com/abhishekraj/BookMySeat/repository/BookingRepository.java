package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;

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

	long countByShowIdAndStatusAndCheckedInAtIsNotNull(Long showId, BookingStatus status);

	// Atomic check-in: 1 row for the first scan, 0 for any later or concurrent one. The version bump makes a
	// cancellation that loaded the booking earlier fail its optimistic lock instead of overwriting the check-in.
	@Modifying
	@Query("""
			update Booking b set b.checkedInAt = :now, b.checkedInBy = :staffUserId, b.version = b.version + 1
			where b.id = :bookingId and b.checkedInAt is null
			  and b.status = com.abhishekraj.BookMySeat.entity.BookingStatus.CONFIRMED""")
	int markCheckedIn(Long bookingId, Instant now, Long staffUserId);

	// Every booking of a show (to cancel or notify them all); the seats come in batched IN (...) queries
	@EntityGraph(attributePaths = {"user"})
	List<Booking> findAllByShowIdAndStatus(Long showId, BookingStatus status);

	@Query("""
			select new com.abhishekraj.BookMySeat.repository.ShowStats$Sales(b.show.id, count(b), coalesce(sum(b.totalAmount), 0))
			from Booking b where b.show.id in :showIds and b.status = :status group by b.show.id""")
	List<ShowStats.Sales> salesByShow(Collection<Long> showIds, BookingStatus status);

	long countByStatus(BookingStatus status);

	long countByStatusAndCreatedAtAfter(BookingStatus status, Instant after);

	@Query("select coalesce(sum(b.totalAmount), 0) from Booking b where b.status = :status")
	BigDecimal sumTotalByStatus(BookingStatus status);

	@Query("select coalesce(sum(b.totalAmount), 0) from Booking b where b.status = :status and b.createdAt > :after")
	BigDecimal sumTotalByStatusAndCreatedAtAfter(BookingStatus status, Instant after);

	// Reviews: the person's confirmed bookings for any screening of the film, locked (FOR UPDATE), so two reviews
	// they save at the same moment take turns and the second edits the first instead of failing on the unique
	// constraint. The film is matched in a subquery, so only booking rows are locked (not the shows)
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select b from Booking b where b.user.id = :userId
			  and b.show.id in (select s.id from Show s where s.film.id = :filmId)
			  and b.status = com.abhishekraj.BookMySeat.entity.BookingStatus.CONFIRMED order by b.id""")
	List<Booking> lockConfirmedForFilm(Long userId, Long filmId);

	// "How was it?": confirmed bookings of shows that ended (start + the film's runtime) before endedBefore and
	// started after startedAfter, and that nobody has looked at yet
	@Query(value = """
			SELECT b.id FROM bookings b JOIN shows s ON s.id = b.show_id JOIN films f ON f.id = s.film_id
			WHERE b.status = 'CONFIRMED' AND b.review_requested_at IS NULL AND s.status = 'SCHEDULED'
			  AND s.start_time + make_interval(mins => f.runtime_minutes) <= :endedBefore
			  AND s.start_time > :startedAfter
			ORDER BY s.start_time, b.id LIMIT :limit""", nativeQuery = true)
	List<Long> findDueForReviewRequest(Instant endedBefore, Instant startedAfter, int limit);

	// SKIP LOCKED: if another instance is handling this booking right now, leave it to them
	@Query(value = """
			SELECT * FROM bookings WHERE id = :id AND status = 'CONFIRMED' AND review_requested_at IS NULL
			FOR UPDATE SKIP LOCKED""", nativeQuery = true)
	Optional<Booking> lockForReviewRequest(Long id);

	// Already asked about this film, through another booking (of this or another screening)
	@Query("""
			select count(b) > 0 from Booking b where b.user.id = :userId and b.show.film.id = :filmId
			  and b.reviewRequestedAt is not null""")
	boolean wasAskedAboutFilm(Long userId, Long filmId);
}

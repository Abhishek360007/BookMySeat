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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShowRepository extends JpaRepository<Show, Long>, JpaSpecificationExecutor<Show> {

	@Override
	@EntityGraph(attributePaths = {"venue", "film"})
	List<Show> findAll();

	@EntityGraph(attributePaths = {"venue", "film"})
	List<Show> findByVenueId(Long venueId);

	// Search: the venue and film are to-ones, so fetching them with paging is safe (one query plus a count)
	@Override
	@EntityGraph(attributePaths = {"venue", "film"})
	Page<Show> findAll(Specification<Show> spec, Pageable pageable);

	// Films' scheduled shows from `now` on, soonest first, with their venues and prices
	// (one query: the prices are a small map per show)
	@Query("""
			select distinct s from Show s join fetch s.venue v left join fetch s.categoryPrices
			where s.film.id in :filmIds and s.status = com.abhishekraj.BookMySeat.entity.ShowStatus.SCHEDULED
			  and s.startTime >= :now
			order by s.startTime, s.id""")
	List<Show> findUpcomingOfFilms(Collection<Long> filmIds, Instant now);

	// Shows per film, for the admin Films page: one grouped query for the whole page
	@Query("select s.film.id, count(s) from Show s where s.film.id in :filmIds group by s.film.id")
	List<Object[]> countByFilmIds(Collection<Long> filmIds);

	// Films with shows created before the cutoff whose followers haven't been told yet (partial index V25)
	@Query(value = """
			SELECT DISTINCT film_id FROM shows WHERE followers_notified_at IS NULL AND created_at <= :cutoff
			ORDER BY film_id LIMIT :limit""", nativeQuery = true)
	List<Long> findFilmsToAnnounce(Instant cutoff, int limit);

	// SKIP LOCKED: shows another run is announcing right now are left to it
	@Query(value = """
			SELECT * FROM shows WHERE film_id = :filmId AND followers_notified_at IS NULL AND created_at <= :cutoff
			ORDER BY start_time, id FOR UPDATE SKIP LOCKED""", nativeQuery = true)
	List<Show> lockToAnnounce(Long filmId, Instant cutoff);

	// A film renamed: its shows carry the new title
	@Modifying
	@Query("update Show s set s.title = :title where s.film.id = :filmId")
	int retitleFilm(Long filmId, String title);

	boolean existsByVenueId(Long venueId);

	long countByStatusAndStartTimeAfter(ShowStatus status, Instant now);

	// With its category prices: for pricing outside a transaction (checkout)
	@EntityGraph(attributePaths = "categoryPrices")
	Optional<Show> findWithPricesById(Long id);

	// FOR SHARE: many bookings can confirm at once, but a show can't be cancelled in the middle of one
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select s from Show s where s.id = :id")
	Optional<Show> findForBookingById(Long id);

	// FOR UPDATE: waits for confirms in progress to commit, and blocks new ones until the cancel commits
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Show s where s.id = :id")
	Optional<Show> findForUpdateById(Long id);
}

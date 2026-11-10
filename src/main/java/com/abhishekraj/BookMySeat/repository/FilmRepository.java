package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Film;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FilmRepository extends JpaRepository<Film, Long> {

	Optional<Film> findByTitleIgnoreCase(String title);

	// A show named after a film that doesn't exist yet creates it. ON CONFLICT: two shows created at once with a new
	// title give one film (the unique index on lower(title) decides), and neither fails
	@Modifying
	@Query(value = """
			INSERT INTO films (title, runtime_minutes, created_at) VALUES (:title, :runtimeMinutes, :now)
			ON CONFLICT ((lower(title))) DO NOTHING""", nativeQuery = true)
	int insertIfAbsent(String title, int runtimeMinutes, Instant now);

	// Atomic, so two reviews saved at once both count. The CHECK on films refuses a count that would go wrong.
	@Modifying
	@Query(value = "UPDATE films SET rating_sum = rating_sum + :sumDelta, rating_count = rating_count + :countDelta WHERE id = :filmId",
			nativeQuery = true)
	int adjustRating(Long filmId, int sumDelta, int countDelta);

	// "Now showing": films with a scheduled show from `now` on (in the city, if given), the soonest screening first.
	// q is a LIKE pattern on the lower-case title (wildcards in the search text already escaped). An empty city or q
	// means any: never null, which would reach Postgres as a parameter without a type.
	@Query(value = """
			SELECT f.id FROM films f JOIN shows s ON s.film_id = f.id JOIN venues v ON v.id = s.venue_id
			WHERE s.status = 'SCHEDULED' AND s.start_time >= :now
			  AND (:city = '' OR lower(v.city) = lower(:city))
			  AND (:q = '' OR lower(f.title) LIKE :q ESCAPE '\\')
			GROUP BY f.id ORDER BY MIN(s.start_time), f.id LIMIT :limit OFFSET :offset""", nativeQuery = true)
	List<Long> findNowShowingIds(Instant now, String city, String q, int limit, long offset);

	@Query(value = """
			SELECT COUNT(DISTINCT f.id) FROM films f JOIN shows s ON s.film_id = f.id JOIN venues v ON v.id = s.venue_id
			WHERE s.status = 'SCHEDULED' AND s.start_time >= :now
			  AND (:city = '' OR lower(v.city) = lower(:city))
			  AND (:q = '' OR lower(f.title) LIKE :q ESCAPE '\\')""", nativeQuery = true)
	long countNowShowing(Instant now, String city, String q);

	// Spring Data escapes % and _ in the search text itself
	Page<Film> findByTitleContainingIgnoreCase(String text, Pageable pageable);
}

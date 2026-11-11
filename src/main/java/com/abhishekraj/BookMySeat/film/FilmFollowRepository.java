package com.abhishekraj.BookMySeat.film;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface FilmFollowRepository extends JpaRepository<FilmFollow, FilmFollow.Id> {

	// ON CONFLICT: following twice (or from two tabs at once) is one follow, and neither fails
	@Modifying
	@Query(value = """
			INSERT INTO film_follows (user_id, film_id, created_at) VALUES (:userId, :filmId, :now)
			ON CONFLICT (user_id, film_id) DO NOTHING""", nativeQuery = true)
	int follow(Long userId, Long filmId, Instant now);

	@Modifying
	@Query(value = "DELETE FROM film_follows WHERE user_id = :userId AND film_id = :filmId", nativeQuery = true)
	int unfollow(Long userId, Long filmId);

	/** The films someone follows, newest follow first: [film id, followed at]. */
	@Query(value = "SELECT film_id, created_at FROM film_follows WHERE user_id = :userId ORDER BY created_at DESC, film_id",
			nativeQuery = true)
	List<Object[]> followedBy(Long userId);

	@Query(value = "SELECT user_id FROM film_follows WHERE film_id = :filmId ORDER BY user_id", nativeQuery = true)
	List<Long> followersOf(Long filmId);
}

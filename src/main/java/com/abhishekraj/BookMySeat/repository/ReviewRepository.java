package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Review;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

	// A film's page: visible reviews with their author (a to-one, so paging stays in the database)
	@EntityGraph(attributePaths = "user")
	Page<Review> findByFilmIdAndHiddenFalse(Long filmId, Pageable pageable);

	// FOR UPDATE: an edit, a delete and an admin hiding the same review take turns, so the film's sum stays right
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Review r where r.film.id = :filmId and r.user.id = :userId")
	Optional<Review> lockByFilmIdAndUserId(Long filmId, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Review r where r.id = :id")
	Optional<Review> lockById(Long id);

	@EntityGraph(attributePaths = {"film", "show"})
	List<Review> findByUserIdOrderByCreatedAtDesc(Long userId);

	@EntityGraph(attributePaths = {"film", "user"})
	Page<Review> findByHidden(boolean hidden, Pageable pageable);

	@Override
	@EntityGraph(attributePaths = {"film", "user"})
	Page<Review> findAll(Pageable pageable);

	boolean existsByFilmIdAndUserId(Long filmId, Long userId);
}

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

	// A show's page: visible reviews with their author (a to-one, so paging stays in the database)
	@EntityGraph(attributePaths = "user")
	Page<Review> findByShowIdAndHiddenFalse(Long showId, Pageable pageable);

	// FOR UPDATE: an edit, a delete and an admin hiding the same review take turns, so the show's sum stays right
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Review r where r.show.id = :showId and r.user.id = :userId")
	Optional<Review> lockByShowIdAndUserId(Long showId, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Review r where r.id = :id")
	Optional<Review> lockById(Long id);

	@EntityGraph(attributePaths = "show")
	List<Review> findByUserIdOrderByCreatedAtDesc(Long userId);

	@EntityGraph(attributePaths = {"show", "user"})
	Page<Review> findByHidden(boolean hidden, Pageable pageable);

	@Override
	@EntityGraph(attributePaths = {"show", "user"})
	Page<Review> findAll(Pageable pageable);

	boolean existsByShowIdAndUserId(Long showId, Long userId);
}

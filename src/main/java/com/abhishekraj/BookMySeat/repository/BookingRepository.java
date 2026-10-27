package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Booking;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	@EntityGraph(attributePaths = {"show", "show.venue", "seats"})
	List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);

	@EntityGraph(attributePaths = {"show", "show.venue", "seats"})
	Optional<Booking> findByIdAndUserId(Long id, Long userId);
}

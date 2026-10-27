package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {
	List<Seat> findByShowIdOrderByIdAsc(Long showId);
	boolean existsByShowId(Long showId);
}

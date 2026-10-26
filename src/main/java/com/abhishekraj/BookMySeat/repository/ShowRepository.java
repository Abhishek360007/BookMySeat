package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Show;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShowRepository extends JpaRepository<Show, Long> {

	@Override
	@EntityGraph(attributePaths = "venue")
	List<Show> findAll();

	@EntityGraph(attributePaths = "venue")
	List<Show> findByVenueId(Long venueId);

	boolean existsByVenueId(Long venueId);
}

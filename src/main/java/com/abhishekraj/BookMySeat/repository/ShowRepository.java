package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Show;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface ShowRepository extends JpaRepository<Show, Long>, JpaSpecificationExecutor<Show> {

	@Override
	@EntityGraph(attributePaths = "venue")
	List<Show> findAll();

	@EntityGraph(attributePaths = "venue")
	List<Show> findByVenueId(Long venueId);

	// Search: the venue is a to-one, so fetching it with paging is safe (one query plus a count)
	@Override
	@EntityGraph(attributePaths = "venue")
	Page<Show> findAll(Specification<Show> spec, Pageable pageable);

	boolean existsByVenueId(Long venueId);
}

package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, Long> {
}

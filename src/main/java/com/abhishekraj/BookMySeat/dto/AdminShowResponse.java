package com.abhishekraj.BookMySeat.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of the admin dashboard: a show with how much of it has sold. */
public record AdminShowResponse(Long id, String title, Instant startTime, BigDecimal price, Long venueId,
		String venueName, String city, long totalSeats, long bookedSeats, long confirmedBookings, BigDecimal revenue) {}

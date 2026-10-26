package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Show;

import java.math.BigDecimal;
import java.time.Instant;

public record ShowResponse(Long id, String title, Instant startTime, BigDecimal price,
		Long venueId, String venueName) {

	public static ShowResponse from(Show show) {
		return new ShowResponse(show.getId(), show.getTitle(), show.getStartTime(), show.getPrice(),
				show.getVenue().getId(), show.getVenue().getName());
	}
}

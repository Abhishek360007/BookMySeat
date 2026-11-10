package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Show;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;

/** One screening of a film, as listed with it: when, where, and its cheapest seat. */
public record ShowSlot(Long showId, Instant startTime, String venueName, String city, BigDecimal fromPrice) {

	public static ShowSlot from(Show show) {
		return new ShowSlot(show.getId(), show.getStartTime(), show.getVenue().getName(), show.getVenue().getCity(),
				Collections.min(show.allPrices().values()));
	}
}

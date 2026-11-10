package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Film;
import com.abhishekraj.BookMySeat.entity.SeatCategory;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.ShowStatus;
import com.abhishekraj.BookMySeat.service.ReviewRules;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * price: the regular price ("from" in listings); prices: every seat category; aisleAfter: gaps in the seat map;
 * filmId and runtimeMinutes: its film; ratingAverage (one decimal, null without reviews) and ratingCount: the film's
 * visible reviews, from every screening.
 */
public record ShowResponse(Long id, String title, Instant startTime, BigDecimal price,
		Long venueId, String venueName, ShowStatus status, Map<SeatCategory, BigDecimal> prices, List<Integer> aisleAfter,
		Double ratingAverage, int ratingCount, Long filmId, int runtimeMinutes) {

	public static ShowResponse from(Show show) {
		Film film = show.getFilm();
		return new ShowResponse(show.getId(), show.getTitle(), show.getStartTime(), show.getPrice(),
				show.getVenue().getId(), show.getVenue().getName(), show.getStatus(), show.allPrices(), show.aisleAfterList(),
				ReviewRules.average(film.getRatingSum(), film.getRatingCount()), film.getRatingCount(), film.getId(),
				film.getRuntimeMinutes());
	}
}

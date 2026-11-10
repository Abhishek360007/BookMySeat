package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Film;
import com.abhishekraj.BookMySeat.service.ReviewRules;

import java.math.BigDecimal;
import java.util.List;

/** A film on the home page: its rating, how many screenings are coming up, the next few, and the cheapest seat. */
public record FilmCardResponse(Long id, String title, int runtimeMinutes, String language, String certificate,
		Double ratingAverage, int ratingCount, int upcomingShows, BigDecimal fromPrice, List<ShowSlot> nextShows) {

	public static final int NEXT_SHOWS = 3;

	public static FilmCardResponse from(Film film, List<ShowSlot> upcoming) {
		BigDecimal from = upcoming.stream().map(ShowSlot::fromPrice).min(BigDecimal::compareTo).orElse(null);
		return new FilmCardResponse(film.getId(), film.getTitle(), film.getRuntimeMinutes(), film.getLanguage(),
				film.getCertificate(), ReviewRules.average(film.getRatingSum(), film.getRatingCount()),
				film.getRatingCount(), upcoming.size(), from, upcoming.stream().limit(NEXT_SHOWS).toList());
	}
}

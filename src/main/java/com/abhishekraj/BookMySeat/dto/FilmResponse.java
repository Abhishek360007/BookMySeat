package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Film;
import com.abhishekraj.BookMySeat.service.ReviewRules;

import java.util.List;

/** A film with its rating (from every screening's reviews) and, on its page, its upcoming screenings. */
public record FilmResponse(Long id, String title, String synopsis, int runtimeMinutes, String language,
		String certificate, Double ratingAverage, int ratingCount, List<ShowSlot> shows) {

	public static FilmResponse from(Film film, List<ShowSlot> shows) {
		return new FilmResponse(film.getId(), film.getTitle(), film.getSynopsis(), film.getRuntimeMinutes(),
				film.getLanguage(), film.getCertificate(), ReviewRules.average(film.getRatingSum(), film.getRatingCount()),
				film.getRatingCount(), shows);
	}
}

package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Film;
import com.abhishekraj.BookMySeat.service.ReviewRules;

/** A film for the admin Films page, with how many shows it has (any time, any status). */
public record AdminFilmResponse(Long id, String title, String synopsis, int runtimeMinutes, String language,
		String certificate, Double ratingAverage, int ratingCount, long shows) {

	public static AdminFilmResponse from(Film film, long shows) {
		return new AdminFilmResponse(film.getId(), film.getTitle(), film.getSynopsis(), film.getRuntimeMinutes(),
				film.getLanguage(), film.getCertificate(), ReviewRules.average(film.getRatingSum(), film.getRatingCount()),
				film.getRatingCount(), shows);
	}
}

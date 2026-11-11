package com.abhishekraj.BookMySeat.film;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A film's poster image, one row per film (the film's id is the key). */
@Entity
@Table(name = "film_posters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FilmPoster {

	@Id
	private Long filmId;

	@Column(nullable = false)
	private byte[] content;

	@Column(nullable = false, length = 16)
	private String version;

	@Column(nullable = false)
	private Instant updatedAt;

	FilmPoster(Long filmId) {
		this.filmId = filmId;
	}

	void replace(PosterImages.Poster poster, Instant now) {
		this.content = poster.jpeg();
		this.version = poster.version();
		this.updatedAt = now;
	}
}

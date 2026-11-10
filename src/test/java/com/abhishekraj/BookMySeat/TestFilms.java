package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Film;
import com.abhishekraj.BookMySeat.repository.FilmRepository;

import java.time.Instant;

/** For tests that save shows straight into the database: every show needs its film. */
public final class TestFilms {

	private TestFilms() {
	}

	/** The film with this title, created (180 minutes) if there is none yet. */
	public static Film named(FilmRepository films, String title) {
		return films.findByTitleIgnoreCase(title)
				.orElseGet(() -> films.save(new Film(title, Film.DEFAULT_RUNTIME_MINUTES, Instant.now())));
	}
}

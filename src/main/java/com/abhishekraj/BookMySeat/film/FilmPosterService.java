package com.abhishekraj.BookMySeat.film;

import com.abhishekraj.BookMySeat.config.CacheConfig;
import com.abhishekraj.BookMySeat.entity.Film;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.FilmRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** Stores, replaces and removes films' posters. The film's poster_version changes with them (cards show it). */
@Service
@RequiredArgsConstructor
public class FilmPosterService {

	/** What an upload may weigh before it is even looked at. */
	public static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

	private final FilmRepository filmRepository;
	private final FilmPosterRepository posterRepository;
	private final Clock clock;

	/** Returns the new poster's version. */
	@CacheEvict(cacheNames = CacheConfig.FILM_SEARCH, allEntries = true)
	@Transactional
	public String upload(Long filmId, byte[] upload) {
		if (upload.length > MAX_UPLOAD_BYTES) {
			throw new PosterRejectedException(PosterRejectedException.Reason.FILE_TOO_BIG, "Posters can be at most 5 MB");
		}
		Film film = findFilm(filmId);
		PosterImages.Poster poster = PosterImages.process(upload);
		FilmPoster row = posterRepository.findById(filmId).orElseGet(() -> new FilmPoster(filmId));
		row.replace(poster, Instant.now(clock));
		posterRepository.save(row);
		film.setPosterVersion(poster.version());
		return poster.version();
	}

	/** Removing a film's poster twice is not an error. */
	@CacheEvict(cacheNames = CacheConfig.FILM_SEARCH, allEntries = true)
	@Transactional
	public void remove(Long filmId) {
		Film film = findFilm(filmId);
		posterRepository.findById(filmId).ifPresent(posterRepository::delete);
		film.setPosterVersion(null);
	}

	@Transactional(readOnly = true)
	public Optional<FilmPoster> poster(Long filmId) {
		return posterRepository.findById(filmId);
	}

	private Film findFilm(Long id) {
		return filmRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Film not found with id " + id));
	}
}

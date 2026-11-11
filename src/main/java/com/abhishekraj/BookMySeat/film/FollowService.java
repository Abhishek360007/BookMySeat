package com.abhishekraj.BookMySeat.film;

import com.abhishekraj.BookMySeat.entity.Film;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.FilmRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Following films: follow, stop, and the list of films someone follows. */
@Service
@RequiredArgsConstructor
public class FollowService {

	/** A followed film: its upcoming showtimes, so the list says which ones have something to book. */
	public record Followed(Long filmId, String title, String posterUrl, int upcomingShows, Instant followedAt) {}

	private final FilmFollowRepository followRepository;
	private final FilmRepository filmRepository;
	private final ShowRepository showRepository;
	private final Clock clock;

	/** Following twice is the same as once. */
	@Transactional
	public void follow(Long userId, Long filmId) {
		requireFilm(filmId);
		followRepository.follow(userId, filmId, Instant.now(clock));
	}

	/** Stopping when not following is not an error. */
	@Transactional
	public void unfollow(Long userId, Long filmId) {
		requireFilm(filmId);
		followRepository.unfollow(userId, filmId);
	}

	/** Newest follow first: three queries whatever the number of films. */
	@Transactional(readOnly = true)
	public List<Followed> followedBy(Long userId) {
		List<Object[]> rows = followRepository.followedBy(userId);
		if (rows.isEmpty()) {
			return List.of();
		}
		List<Long> ids = rows.stream().map(row -> ((Number) row[0]).longValue()).toList();
		Map<Long, Film> films = filmRepository.findAllById(ids).stream().collect(Collectors.toMap(Film::getId, Function.identity()));
		Map<Long, Long> upcoming = showRepository.findUpcomingOfFilms(ids, Instant.now(clock)).stream()
				.collect(Collectors.groupingBy(show -> show.getFilm().getId(), Collectors.counting()));
		return rows.stream().map(row -> {
			Film film = films.get(((Number) row[0]).longValue());
			return new Followed(film.getId(), film.getTitle(), film.posterUrl(),
					upcoming.getOrDefault(film.getId(), 0L).intValue(), instant(row[1]));
		}).toList();
	}

	@Transactional(readOnly = true)
	public boolean follows(Long userId, Long filmId) {
		return followRepository.existsById(new FilmFollow.Id(userId, filmId));
	}

	private void requireFilm(Long filmId) {
		if (!filmRepository.existsById(filmId)) {
			throw new ResourceNotFoundException("Film not found with id " + filmId);
		}
	}

	// The native query's timestamp, as the driver gives it
	private static Instant instant(Object value) {
		return switch (value) {
			case Instant i -> i;
			case OffsetDateTime o -> o.toInstant();
			case Timestamp t -> t.toInstant();
			default -> throw new IllegalStateException("Unexpected timestamp " + value.getClass());
		};
	}
}

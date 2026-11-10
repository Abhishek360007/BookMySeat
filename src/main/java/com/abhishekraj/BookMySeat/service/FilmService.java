package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.config.CacheConfig;
import com.abhishekraj.BookMySeat.dto.AdminFilmResponse;
import com.abhishekraj.BookMySeat.dto.FilmCardResponse;
import com.abhishekraj.BookMySeat.dto.FilmRequest;
import com.abhishekraj.BookMySeat.dto.FilmResponse;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.dto.ShowSlot;
import com.abhishekraj.BookMySeat.entity.Film;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.FilmRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.ShowSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Films: the home page's "now showing", a film's page with its screenings, and the admin's films. */
@Service
@RequiredArgsConstructor
public class FilmService {

	public static final int MAX_PAGE = 50;

	private final FilmRepository filmRepository;
	private final ShowRepository showRepository;
	private final Clock clock;

	/**
	 * Films with a screening coming up (in the city, if given; title containing q, if given), the soonest screening
	 * first: three queries for any page size (the page of ids and its count, the films, their screenings).
	 */
	@Cacheable(CacheConfig.FILM_SEARCH)
	@Transactional(readOnly = true)
	public PageResponse<FilmCardResponse> nowShowing(String q, String city, int page, int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be 0 or more and size 1 to " + MAX_PAGE);
		}
		Instant now = Instant.now(clock);
		String cityFilter = blankToNull(city);
		String pattern = like(q);
		String cityArg = cityFilter == null ? "" : cityFilter;
		String patternArg = pattern == null ? "" : pattern;
		List<Long> ids = filmRepository.findNowShowingIds(now, cityArg, patternArg, size, (long) page * size);
		long total = filmRepository.countNowShowing(now, cityArg, patternArg);

		Map<Long, Film> films = filmRepository.findAllById(ids).stream()
				.collect(Collectors.toMap(Film::getId, film -> film));
		Map<Long, List<ShowSlot>> shows = upcoming(ids, now, cityFilter);
		List<FilmCardResponse> cards = ids.stream()
				.map(id -> FilmCardResponse.from(films.get(id), shows.getOrDefault(id, List.of())))
				.toList();
		return new PageResponse<>(cards, page, size, total, (int) ((total + size - 1) / size));
	}

	/** A film with every screening still to come. */
	@Transactional(readOnly = true)
	public FilmResponse film(Long id) {
		Film film = findFilm(id);
		Instant now = Instant.now(clock);
		return FilmResponse.from(film, upcoming(List.of(id), now, null).getOrDefault(id, List.of()));
	}

	@Transactional(readOnly = true)
	public PageResponse<AdminFilmResponse> adminList(String q, Pageable pageable) {
		String text = blankToNull(q);
		Page<Film> films = text == null ? filmRepository.findAll(pageable)
				: filmRepository.findByTitleContainingIgnoreCase(text, pageable);
		Map<Long, Long> shows = new HashMap<>();
		if (films.hasContent()) {
			for (Object[] row : showRepository.countByFilmIds(films.map(Film::getId).getContent())) {
				shows.put((Long) row[0], (Long) row[1]);
			}
		}
		return PageResponse.of(films, film -> AdminFilmResponse.from(film, shows.getOrDefault(film.getId(), 0L)));
	}

	@CacheEvict(cacheNames = CacheConfig.FILM_SEARCH, allEntries = true)
	@Transactional
	public AdminFilmResponse create(FilmRequest request) {
		String title = request.title().strip();
		if (filmRepository.findByTitleIgnoreCase(title).isPresent()) {
			throw new ConflictException("There is already a film called " + title);
		}
		Film film = new Film(title, request.runtimeMinutes(), Instant.now(clock));
		apply(film, request);
		return AdminFilmResponse.from(filmRepository.save(film), 0);
	}

	/** A new title is carried to the film's shows, so their emails, tickets and bookings say it too. */
	@Caching(evict = {
			@CacheEvict(cacheNames = CacheConfig.SHOW, allEntries = true),
			@CacheEvict(cacheNames = {CacheConfig.SHOW_SEARCH, CacheConfig.FILM_SEARCH}, allEntries = true)})
	@Transactional
	public AdminFilmResponse update(Long id, FilmRequest request) {
		Film film = findFilm(id);
		String title = request.title().strip();
		filmRepository.findByTitleIgnoreCase(title).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
			throw new ConflictException("There is already a film called " + title);
		});
		boolean retitled = !film.getTitle().equals(title);
		film.setTitle(title);
		film.setRuntimeMinutes(request.runtimeMinutes());
		apply(film, request);
		filmRepository.flush();
		if (retitled) {
			showRepository.retitleFilm(id, title);
		}
		return AdminFilmResponse.from(film, showRepository.countByFilmIds(List.of(id)).stream()
				.mapToLong(row -> (Long) row[1]).sum());
	}

	private void apply(Film film, FilmRequest request) {
		film.setSynopsis(blankToNull(request.synopsis()));
		film.setLanguage(blankToNull(request.language()));
		film.setCertificate(blankToNull(request.certificate()));
	}

	private Map<Long, List<ShowSlot>> upcoming(List<Long> filmIds, Instant now, String city) {
		if (filmIds.isEmpty()) {
			return Map.of();
		}
		// The city is matched here: a page of films has few screenings, and a null parameter in the query would
		// reach Postgres without a type
		return showRepository.findUpcomingOfFilms(filmIds, now).stream()
				.filter(show -> city == null || show.getVenue().getCity().equalsIgnoreCase(city))
				.sorted(Comparator.comparing(Show::getStartTime).thenComparing(Show::getId))
				.collect(Collectors.groupingBy(show -> show.getFilm().getId(),
						Collectors.mapping(ShowSlot::from, Collectors.toList())));
	}

	private Film findFilm(Long id) {
		return filmRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Film not found with id " + id));
	}

	/** The title search as a LIKE pattern on the lower-case title, its wildcards taken as text; null for none. */
	private static String like(String q) {
		String text = blankToNull(q);
		return text == null ? null : "%" + ShowSpecifications.escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}

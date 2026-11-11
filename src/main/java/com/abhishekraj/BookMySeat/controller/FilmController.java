package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.FilmCardResponse;
import com.abhishekraj.BookMySeat.dto.FilmResponse;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.film.FilmPoster;
import com.abhishekraj.BookMySeat.film.FilmPosterService;
import com.abhishekraj.BookMySeat.service.FilmService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@Tag(name = "Films", description = "Films and their screenings: what's showing, and a film's page.")
@RestController
@RequestMapping("/api/films")
@RequiredArgsConstructor
public class FilmController {

	private final FilmService filmService;
	private final FilmPosterService posterService;

	@Operation(summary = "Films now showing", description = "Films with a screening coming up, the soonest first, "
			+ "each with its rating, number of upcoming screenings, the next three and the cheapest seat. "
			+ "Optional: q (in the title), city (where the screenings are).")
	@GetMapping
	public PageResponse<FilmCardResponse> nowShowing(@RequestParam(required = false) String q,
			@RequestParam(required = false) String city,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "12") int size) {
		return filmService.nowShowing(q, city, page, size);
	}

	@Operation(summary = "A film's poster (480 x 720 JPEG)", description = "posterUrl in the film's details has the "
			+ "poster's version in it, so the image is cached for a year; If-None-Match with its ETag gives 304.")
	@GetMapping(value = "/{id}/poster", produces = MediaType.IMAGE_JPEG_VALUE)
	public ResponseEntity<byte[]> poster(@PathVariable Long id, WebRequest request) {
		FilmPoster poster = posterService.poster(id)
				.orElseThrow(() -> new ResourceNotFoundException("Film " + id + " has no poster"));
		String etag = "\"" + poster.getVersion() + "\"";
		if (request.checkNotModified(etag)) {
			return null; // 304, headers set by checkNotModified
		}
		return ResponseEntity.ok()
				.eTag(etag)
				.cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
				.contentType(MediaType.IMAGE_JPEG)
				.body(poster.getContent());
	}

	@Operation(summary = "A film", description = "Its details, rating and every screening still to come.")
	@GetMapping("/{id}")
	public FilmResponse film(@PathVariable Long id) {
		return filmService.film(id);
	}
}

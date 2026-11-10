package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.FilmCardResponse;
import com.abhishekraj.BookMySeat.dto.FilmResponse;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.service.FilmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Films", description = "Films and their screenings: what's showing, and a film's page.")
@RestController
@RequestMapping("/api/films")
@RequiredArgsConstructor
public class FilmController {

	private final FilmService filmService;

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

	@Operation(summary = "A film", description = "Its details, rating and every screening still to come.")
	@GetMapping("/{id}")
	public FilmResponse film(@PathVariable Long id) {
		return filmService.film(id);
	}
}

package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.AdminFilmResponse;
import com.abhishekraj.BookMySeat.dto.FilmRequest;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.service.FilmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Secured as ADMIN-only in SecurityConfig ("/api/admin/**")
@Tag(name = "Admin films", description = "Create and edit films; shows pick their film. ADMIN only.")
@RestController
@RequestMapping("/api/admin/films")
@RequiredArgsConstructor
public class AdminFilmController {

	private final FilmService filmService;

	@Operation(summary = "Films with how many shows each has", description = "q searches the title; by title unless sort= is given.")
	@GetMapping
	public PageResponse<AdminFilmResponse> list(@RequestParam(required = false) String q,
			@PageableDefault(size = 50, sort = "title", direction = Sort.Direction.ASC) Pageable pageable) {
		return filmService.adminList(q, pageable);
	}

	@Operation(summary = "Create a film", description = "Titles are unique ignoring case (409 for a duplicate).")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AdminFilmResponse create(@Valid @RequestBody FilmRequest request) {
		return filmService.create(request);
	}

	@Operation(summary = "Edit a film", description = "A new title is carried to its shows; a new runtime moves "
			+ "the end of their calendar events and when people are asked how it was.")
	@PutMapping("/{id}")
	public AdminFilmResponse update(@PathVariable Long id, @Valid @RequestBody FilmRequest request) {
		return filmService.update(id, request);
	}
}

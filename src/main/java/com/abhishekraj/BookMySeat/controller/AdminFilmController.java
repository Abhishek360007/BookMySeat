package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.AdminFilmResponse;
import com.abhishekraj.BookMySeat.dto.FilmRequest;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.film.FilmPosterService;
import com.abhishekraj.BookMySeat.film.PosterRejectedException;
import com.abhishekraj.BookMySeat.service.FilmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

// Secured as ADMIN-only in SecurityConfig ("/api/admin/**")
@Tag(name = "Admin films", description = "Create and edit films; shows pick their film. ADMIN only.")
@RestController
@RequestMapping("/api/admin/films")
@RequiredArgsConstructor
public class AdminFilmController {

	private final FilmService filmService;
	private final FilmPosterService posterService;

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

	@Operation(summary = "Upload a poster", description = "Multipart field \"file\": a JPEG or PNG, at most 5 MB and "
			+ "8000 pixels a side, at least 200. Stored as a 480 x 720 JPEG cropped to 2:3 around the centre; "
			+ "replaces the poster there was. 415 for another type, 422 when it can't be used, 413 when too big.")
	@PutMapping(value = "/{id}/poster", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, String> uploadPoster(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
		if (file.getSize() > FilmPosterService.MAX_UPLOAD_BYTES) {
			throw new PosterRejectedException(PosterRejectedException.Reason.FILE_TOO_BIG, "Posters can be at most 5 MB");
		}
		return Map.of("posterVersion", posterService.upload(id, file.getBytes()));
	}

	@Operation(summary = "Remove a film's poster")
	@DeleteMapping("/{id}/poster")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removePoster(@PathVariable Long id) {
		posterService.remove(id);
	}

	@Operation(summary = "Edit a film", description = "A new title is carried to its shows; a new runtime moves "
			+ "the end of their calendar events and when people are asked how it was.")
	@PutMapping("/{id}")
	public AdminFilmResponse update(@PathVariable Long id, @Valid @RequestBody FilmRequest request) {
		return filmService.update(id, request);
	}
}

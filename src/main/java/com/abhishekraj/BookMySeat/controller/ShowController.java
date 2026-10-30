package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.dto.ShowRequest;
import com.abhishekraj.BookMySeat.dto.ShowResponse;
import com.abhishekraj.BookMySeat.service.ShowService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Tag(name = "Shows", description = "Browse and search shows (public). Creating, updating and deleting needs an ADMIN token.")
@RestController
@RequestMapping("/api/shows")
@RequiredArgsConstructor
public class ShowController {

	private final ShowService showService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ShowResponse create(@Valid @RequestBody ShowRequest request) {
		return showService.create(request);
	}

	@SecurityRequirements
	@GetMapping
	public List<ShowResponse> getAll(@RequestParam(required = false) Long venueId) {
		return showService.getAll(venueId);
	}

	// e.g. /api/shows/search?city=Mumbai&q=dune&from=2027-01-01T00:00:00Z&page=0&size=20
	@SecurityRequirements
	@GetMapping("/search")
	public PageResponse<ShowResponse> search(@RequestParam(required = false) @Size(max = 50) String city,
			@RequestParam(name = "q", required = false) @Size(max = 100) String text,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@PageableDefault(size = 20, sort = "startTime") Pageable pageable) {
		return showService.search(city, text, from, to, pageable);
	}

	@SecurityRequirements
	@GetMapping("/{id}")
	public ShowResponse getById(@PathVariable Long id) {
		return showService.getById(id);
	}

	@PutMapping("/{id}")
	public ShowResponse update(@PathVariable Long id, @Valid @RequestBody ShowRequest request) {
		return showService.update(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		showService.delete(id);
	}
}

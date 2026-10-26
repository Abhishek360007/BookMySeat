package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.ShowRequest;
import com.abhishekraj.BookMySeat.dto.ShowResponse;
import com.abhishekraj.BookMySeat.service.ShowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

	@GetMapping
	public List<ShowResponse> getAll(@RequestParam(required = false) Long venueId) {
		return showService.getAll(venueId);
	}

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

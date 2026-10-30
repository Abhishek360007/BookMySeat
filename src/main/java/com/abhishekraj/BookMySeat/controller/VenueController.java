package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.VenueRequest;
import com.abhishekraj.BookMySeat.dto.VenueResponse;
import com.abhishekraj.BookMySeat.service.VenueService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Venues", description = "Browse venues (public). Creating, updating and deleting needs an ADMIN token.")
@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

	private final VenueService venueService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public VenueResponse create(@Valid @RequestBody VenueRequest request) {
		return venueService.create(request);
	}

	@SecurityRequirements
	@GetMapping
	public List<VenueResponse> getAll() {
		return venueService.getAll();
	}

	@SecurityRequirements
	@GetMapping("/{id}")
	public VenueResponse getById(@PathVariable Long id) {
		return venueService.getById(id);
	}

	@PutMapping("/{id}")
	public VenueResponse update(@PathVariable Long id, @Valid @RequestBody VenueRequest request) {
		return venueService.update(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		venueService.delete(id);
	}
}

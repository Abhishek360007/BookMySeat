package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.GenerateSeatsRequest;
import com.abhishekraj.BookMySeat.dto.SeatResponse;
import com.abhishekraj.BookMySeat.service.SeatService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Seats", description = "Seat map of a show (public, includes live HELD status). Generating seats needs an ADMIN token.")
@RestController
@RequestMapping("/api/shows/{showId}/seats")
@RequiredArgsConstructor
public class SeatController {

	private final SeatService seatService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public List<SeatResponse> generate(@PathVariable Long showId,
			@Valid @RequestBody GenerateSeatsRequest request) {
		return seatService.generateSeats(showId, request);
	}

	@SecurityRequirements
	@GetMapping
	public List<SeatResponse> getSeats(@PathVariable Long showId) {
		return seatService.getSeats(showId);
	}
}

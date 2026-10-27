package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.GenerateSeatsRequest;
import com.abhishekraj.BookMySeat.dto.SeatResponse;
import com.abhishekraj.BookMySeat.service.SeatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

	@GetMapping
	public List<SeatResponse> getSeats(@PathVariable Long showId) {
		return seatService.getSeats(showId);
	}
}

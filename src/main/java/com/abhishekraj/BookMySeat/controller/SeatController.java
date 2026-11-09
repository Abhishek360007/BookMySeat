package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.BestSeatsResponse;
import com.abhishekraj.BookMySeat.entity.SeatCategory;
import com.abhishekraj.BookMySeat.service.BestSeatService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.abhishekraj.BookMySeat.dto.GenerateSeatsRequest;
import com.abhishekraj.BookMySeat.dto.SeatResponse;
import com.abhishekraj.BookMySeat.service.SeatService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.abhishekraj.BookMySeat.dto.SeatLayoutRequest;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PutMapping;
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
	private final BestSeatService bestSeatService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public List<SeatResponse> generate(@PathVariable Long showId,
			@Valid @RequestBody GenerateSeatsRequest request) {
		return seatService.generateSeats(showId, request);
	}

	@Operation(summary = "Change which rows are premium and recliner (ADMIN)",
			description = "Rows are counted from the back. Existing bookings keep what they cost; new holds use the new categories.")
	@PutMapping("/layout")
	public List<SeatResponse> layout(@PathVariable Long showId, @Valid @RequestBody SeatLayoutRequest request) {
		return seatService.applyLayout(showId, request);
	}

	@SecurityRequirements
	@GetMapping
	public List<SeatResponse> getSeats(@PathVariable Long showId) {
		return seatService.getSeats(showId);
	}

	@Operation(summary = "The best free seats together",
			description = "count seats next to each other in one row (no aisle between them), nearest the middle of the "
					+ "row and of the seats about two thirds back; optionally only one category. Nothing is held: to hold "
					+ "them in the same step use POST /api/bookings/hold/best. 409 when no row has that many free together.")
	@SecurityRequirements
	@GetMapping("/best")
	public BestSeatsResponse best(@PathVariable Long showId,
			@RequestParam @Min(1) @Max(10) int count,
			@RequestParam(required = false) SeatCategory category) {
		return bestSeatService.suggest(showId, count, category);
	}
}

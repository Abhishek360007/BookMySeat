package com.abhishekraj.BookMySeat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** seats: how many seats you want together (1 to 6); an offer always has exactly this many. */
public record WaitlistRequest(
		@NotNull Long showId,
		@NotNull @Min(1) @Max(6) Integer seats
) {}

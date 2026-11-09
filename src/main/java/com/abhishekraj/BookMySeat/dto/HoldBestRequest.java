package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.SeatCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record HoldBestRequest(
		@NotNull Long showId,
		@NotNull @Min(1) @Max(10) Integer count,
		// Optional: only seats of this category
		SeatCategory category
) {}

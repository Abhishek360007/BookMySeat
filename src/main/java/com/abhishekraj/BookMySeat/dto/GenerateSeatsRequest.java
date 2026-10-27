package com.abhishekraj.BookMySeat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record GenerateSeatsRequest(
		@Min(1) @Max(26) int rows,
		@Min(1) @Max(30) int seatsPerRow
) {}

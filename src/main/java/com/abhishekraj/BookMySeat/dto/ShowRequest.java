package com.abhishekraj.BookMySeat.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public record ShowRequest(
		@NotBlank @Size(max = 150) String title,
		@NotNull @Future Instant startTime,
		@NotNull @Positive BigDecimal price,
		@NotNull Long venueId
) {}

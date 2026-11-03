package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.SeatCategory;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ShowRequest(
		@NotBlank @Size(max = 150) String title,
		@NotNull @Future Instant startTime,
		@NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
		@NotNull Long venueId,
		// Optional: prices of PREMIUM and RECLINER seats (REGULAR seats cost `price`). Leaving one out = regular price.
		Map<SeatCategory, @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal> categoryPrices,
		// Optional: seat numbers after which the seat map shows an aisle, e.g. [4, 8]
		@Size(max = 5) List<@NotNull @Min(1) @Max(29) Integer> aisleAfter
) {}

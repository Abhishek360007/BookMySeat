package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.SeatCategory;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The film: filmId, or a title (the film with that title, ignoring case, or a new one; runtimeMinutes is only used
 * for a new film, 180 when left out). With both, filmId wins.
 */
public record ShowRequest(
		@Size(max = 150) String title,
		@NotNull @Future Instant startTime,
		@NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
		@NotNull Long venueId,
		// Optional: prices of PREMIUM and RECLINER seats (REGULAR seats cost `price`). Leaving one out = regular price.
		Map<SeatCategory, @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal> categoryPrices,
		// Optional: seat numbers after which the seat map shows an aisle, e.g. [4, 8]
		@Size(max = 5) List<@NotNull @Min(1) @Max(29) Integer> aisleAfter,
		Long filmId,
		@Min(1) @Max(600) Integer runtimeMinutes
) {}

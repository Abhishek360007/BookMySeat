package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.service.ReviewRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A rating from 1 to 5 and an optional text (blank means none). */
public record ReviewRequest(
		@NotNull @Min(1) @Max(5) Integer rating,
		@Size(max = ReviewRules.MAX_TEXT) String text
) {}

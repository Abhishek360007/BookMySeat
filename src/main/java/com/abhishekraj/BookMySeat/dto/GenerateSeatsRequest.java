package com.abhishekraj.BookMySeat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Rows are lettered from the screen: A is the front. Optional premiumRows and reclinerRows are counted from the
 * back: the last reclinerRows rows are recliners, the premiumRows before them premium, the rest regular.
 */
public record GenerateSeatsRequest(
		@Min(1) @Max(26) int rows,
		@Min(1) @Max(30) int seatsPerRow,
		@Min(0) @Max(26) Integer premiumRows,
		@Min(0) @Max(26) Integer reclinerRows
) {

	public GenerateSeatsRequest(int rows, int seatsPerRow) {
		this(rows, seatsPerRow, 0, 0);
	}

	public int premium() {
		return premiumRows == null ? 0 : premiumRows;
	}

	public int recliner() {
		return reclinerRows == null ? 0 : reclinerRows;
	}

	@JsonIgnore
	@AssertTrue(message = "premiumRows + reclinerRows can't be more than rows")
	public boolean isLayoutValid() {
		return premium() + recliner() <= rows;
	}
}

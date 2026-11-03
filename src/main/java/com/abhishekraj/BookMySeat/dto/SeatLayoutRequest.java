package com.abhishekraj.BookMySeat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Which rows, counted from the back, are recliners and which (in front of them) premium; the rest are regular. */
public record SeatLayoutRequest(@Min(0) @Max(26) int premiumRows, @Min(0) @Max(26) int reclinerRows) {}

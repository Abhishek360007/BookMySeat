package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.SeatCategory;

import java.math.BigDecimal;
import java.util.List;

/** The best free seats next to each other, left to right, and what they cost together now. Nothing is held. */
public record BestSeatsResponse(Long showId, List<Long> seatIds, List<String> seatNumbers, SeatCategory category,
		BigDecimal totalAmount) {}

package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatCategory;

import java.math.BigDecimal;

public record SeatResponse(Long id, String seatNumber, String status, SeatCategory category, BigDecimal price) {

	public static SeatResponse from(Seat seat) {
		return new SeatResponse(seat.getId(), seat.getSeatNumber(), seat.getStatus().name(), seat.getCategory(),
				seat.getShow().priceOf(seat.getCategory()));
	}
}

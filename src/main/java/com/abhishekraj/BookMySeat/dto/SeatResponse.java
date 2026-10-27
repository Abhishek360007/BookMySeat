package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Seat;

public record SeatResponse(Long id, String seatNumber, String status) {

	public static SeatResponse from(Seat seat) {
		return new SeatResponse(seat.getId(), seat.getSeatNumber(), seat.getStatus().name());
	}
}

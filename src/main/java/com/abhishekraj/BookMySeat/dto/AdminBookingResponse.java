package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.entity.Seat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record AdminBookingResponse(Long id, String userEmail, List<String> seats,
		BigDecimal totalAmount, BookingStatus status, Instant createdAt) {

	public static AdminBookingResponse from(Booking booking) {
		List<String> seatNumbers = booking.getSeats().stream()
				.sorted(Comparator.comparing(Seat::getId))
				.map(Seat::getSeatNumber).toList();
		return new AdminBookingResponse(booking.getId(), booking.getUser().getEmail(), seatNumbers,
				booking.getTotalAmount(), booking.getStatus(), booking.getCreatedAt());
	}
}

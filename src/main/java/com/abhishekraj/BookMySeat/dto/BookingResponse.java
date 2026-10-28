package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.Show;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record BookingResponse(Long id, Long showId, String showTitle, String venueName,
		Instant showStartTime, BookingStatus status, List<String> seats, BigDecimal totalAmount,
		Instant createdAt, Instant cancelledAt) {

	public static BookingResponse from(Booking booking) {
		Show show = booking.getShow();
		List<String> seatNumbers = booking.getSeats().stream()
				.sorted(Comparator.comparing(Seat::getId))
				.map(Seat::getSeatNumber).toList();
		return new BookingResponse(booking.getId(), show.getId(), show.getTitle(), show.getVenue().getName(),
				show.getStartTime(), booking.getStatus(), seatNumbers, booking.getTotalAmount(),
				booking.getCreatedAt(), booking.getCancelledAt());
	}
}

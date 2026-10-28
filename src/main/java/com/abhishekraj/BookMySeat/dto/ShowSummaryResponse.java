package com.abhishekraj.BookMySeat.dto;

import java.math.BigDecimal;

public record ShowSummaryResponse(Long showId, long totalSeats, long booked, long held,
		long available, long confirmedBookings, BigDecimal revenue) {}

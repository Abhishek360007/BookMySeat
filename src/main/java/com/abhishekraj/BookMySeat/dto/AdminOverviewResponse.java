package com.abhishekraj.BookMySeat.dto;

import java.math.BigDecimal;

/** Headline numbers for the admin dashboard. Revenue counts confirmed bookings only. */
public record AdminOverviewResponse(long upcomingShows, long confirmedBookings, BigDecimal revenue,
		long bookingsLast7Days, BigDecimal revenueLast7Days, long upcomingSeats, long upcomingSeatsBooked) {}

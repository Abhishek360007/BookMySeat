package com.abhishekraj.BookMySeat.repository;

import java.math.BigDecimal;

/** Aggregates for many shows at once (one GROUP BY query each), so a dashboard page never queries per show. */
public final class ShowStats {

	private ShowStats() {}

	public record Seats(Long showId, long total, long booked) {}

	public record Sales(Long showId, long bookings, BigDecimal revenue) {}

	public record SeatTotals(long total, long booked) {}
}

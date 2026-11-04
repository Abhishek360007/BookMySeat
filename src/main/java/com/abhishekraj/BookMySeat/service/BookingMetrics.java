package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatCategory;
import com.abhishekraj.BookMySeat.support.AfterCommit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Booking counters, recorded only once the booking's transaction has committed: a confirm that loses the
 * optimistic-lock race at commit is a conflict, not a booking. Tags stay low-cardinality (never show or user ids).
 */
@Component
public class BookingMetrics {

	private final MeterRegistry meterRegistry;

	public BookingMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void confirmed(Booking booking) {
		// Read now, while the entity is attached; counted after commit
		List<SeatCategory> categories = booking.getSeats().stream().map(Seat::getCategory).toList();
		BigDecimal total = booking.getTotalAmount();
		AfterCommit.run(() -> {
			meterRegistry.counter("bookings.confirmed").increment();
			categories.forEach(category ->
					meterRegistry.counter("bookings.seats", "category", category.name().toLowerCase(Locale.ROOT)).increment());
			meterRegistry.counter("bookings.revenue").increment(total.doubleValue());
		});
	}

	// Runs after the cancel commits; covers customers cancelling and whole shows being cancelled
	@TransactionalEventListener
	public void cancelled(BookingCancelledEvent event) {
		meterRegistry.counter("bookings.cancelled", "by", event.showCancelled() ? "show" : "customer").increment();
	}
}

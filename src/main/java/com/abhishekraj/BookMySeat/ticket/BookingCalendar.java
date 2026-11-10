package com.abhishekraj.BookMySeat.ticket;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.Venue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A booking as a calendar event: in the confirmation email, the reschedule email and as a download. It lasts the
 * film's runtime. The uid is the booking's, so every version is the same event; the sequence is the minute it was
 * made, so a later file (a new show time) wins over an earlier one in a calendar that has both.
 */
@Component
public class BookingCalendar {

	static final Duration REMINDER = Duration.ofHours(1);

	private final TicketCodes ticketCodes;
	private final Clock clock;
	private final String bookingsUrl;

	public BookingCalendar(TicketCodes ticketCodes, Clock clock, @Value("${email.public-url}") String publicUrl) {
		this.ticketCodes = ticketCodes;
		this.clock = clock;
		this.bookingsUrl = publicUrl.replaceAll("/+$", "") + "/#/bookings";
	}

	public String filename(Booking booking) {
		return "booking-" + booking.getId() + ".ics";
	}

	public byte[] ics(Booking booking) {
		Show show = booking.getShow();
		Instant now = Instant.now(clock);
		String seats = booking.getSeats().stream().sorted(Comparator.comparing(Seat::getId))
				.map(Seat::getSeatNumber).collect(Collectors.joining(", "));
		String description = """
				Booking #%d, seats %s
				Show the QR code of your ticket at the entrance (it's in your confirmation email and in My bookings).
				Ticket code: %s
				My bookings: %s""".formatted(booking.getId(), seats, ticketCodes.codeFor(booking.getId()), bookingsUrl);
		return CalendarEvents.ics(new CalendarEvents.Event("booking-" + booking.getId() + "@bookmyseat",
				(int) (now.getEpochSecond() / 60), now, show.getStartTime(), show.getFilm().runtime(), show.getTitle(),
				location(show.getVenue()), description, bookingsUrl, REMINDER));
	}

	private static String location(Venue venue) {
		return Stream.of(venue.getName(), venue.getAddress(), venue.getCity())
				.filter(part -> part != null && !part.isBlank())
				.collect(Collectors.joining(", "));
	}
}

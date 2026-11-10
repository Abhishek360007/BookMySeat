package com.abhishekraj.BookMySeat.ticket;

import com.abhishekraj.BookMySeat.dto.TicketResponse;
import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

@Service
@RequiredArgsConstructor
public class TicketService {

	private final BookingRepository bookingRepository;
	private final TicketCodes codes;
	private final BookingCalendar calendar;

	/** The ticket of one of the user's own confirmed bookings (someone else's is a 404, like the booking itself). */
	@Transactional(readOnly = true)
	public TicketResponse ticket(Long userId, Long bookingId) {
		return toTicket(confirmedBooking(userId, bookingId));
	}

	/** The booking as a calendar event (.ics), with the show's current time. */
	@Transactional(readOnly = true)
	public CalendarFile calendarEvent(Long userId, Long bookingId) {
		Booking booking = confirmedBooking(userId, bookingId);
		return new CalendarFile(calendar.filename(booking), calendar.ics(booking));
	}

	public record CalendarFile(String filename, byte[] content) {}

	private Booking confirmedBooking(Long userId, Long bookingId) {
		Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found with id " + bookingId));
		if (booking.getStatus() != BookingStatus.CONFIRMED) {
			throw new ConflictException("Booking " + bookingId + " is cancelled, so it has no ticket");
		}
		return booking;
	}

	public TicketResponse toTicket(Booking booking) {
		return new TicketResponse(booking.getId(), codes.codeFor(booking.getId()), booking.getShow().getTitle(),
				booking.getShow().getVenue().getName(), booking.getShow().getStartTime(),
				booking.getSeats().stream().sorted(Comparator.comparing(Seat::getId)).map(Seat::getSeatNumber).toList(),
				booking.getUser().getName(), booking.getCheckedInAt());
	}

	public byte[] qrPng(TicketResponse ticket) {
		return QrCodes.png(ticket.code(), 360);
	}
}

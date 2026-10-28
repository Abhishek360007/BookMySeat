package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.BookingResponse;
import com.abhishekraj.BookMySeat.dto.ConfirmBookingRequest;
import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatStatus;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.User;
import com.abhishekraj.BookMySeat.entity.Venue;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Plain unit test: no Spring, no containers. "Now" is fixed at 10:00 UTC, cutoff is 120 minutes.
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
	private static final Long USER_ID = 7L;
	private static final Long BOOKING_ID = 1L;

	@Mock BookingRepository bookingRepository;
	@Mock SeatRepository seatRepository;
	@Mock ShowRepository showRepository;
	@Mock UserRepository userRepository;
	@Mock SeatHoldService seatHoldService;

	private BookingService bookingService;

	@BeforeEach
	void setUp() {
		bookingService = new BookingService(bookingRepository, seatRepository, showRepository, userRepository,
				seatHoldService, Clock.fixed(NOW, ZoneOffset.UTC), 120, new SimpleMeterRegistry());
	}

	@Test
	void cancel_twoHoursAndOneSecondBefore_succeeds() {
		Booking booking = bookingForShowAt("2026-11-01T12:00:01Z");

		BookingResponse response = bookingService.cancel(USER_ID, BOOKING_ID);

		assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(booking.getSeats()).allMatch(seat -> seat.getStatus() == SeatStatus.AVAILABLE);
	}

	@Test
	void cancel_exactlyAtCutoff_succeeds() {
		bookingForShowAt("2026-11-01T12:00:00Z");

		assertThat(bookingService.cancel(USER_ID, BOOKING_ID).status()).isEqualTo(BookingStatus.CANCELLED);
	}

	@Test
	void cancel_oneSecondPastCutoff_isRejected() {
		Booking booking = bookingForShowAt("2026-11-01T11:59:59Z");

		assertThatThrownBy(() -> bookingService.cancel(USER_ID, BOOKING_ID))
				.isInstanceOf(ConflictException.class)
				.hasMessage("Cancellations close 120 minutes before the show starts");
		assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(booking.getSeats()).allMatch(seat -> seat.getStatus() == SeatStatus.BOOKED);
	}

	@Test
	void confirm_showAlreadyStarted_isRejected() {
		Show show = show("2026-11-01T09:00:00Z");
		when(showRepository.findById(show.getId())).thenReturn(Optional.of(show));

		assertThatThrownBy(() -> bookingService.confirm(USER_ID, new ConfirmBookingRequest(show.getId(), List.of(100L))))
				.isInstanceOf(ConflictException.class)
				.hasMessage("Show 1 has already started");
		verify(seatHoldService, never()).isHeldBy(any(), any(), any());
		verify(bookingRepository, never()).save(any());
	}

	private Booking bookingForShowAt(String startTime) {
		Show show = show(startTime);
		Seat seat = new Seat();
		seat.setId(100L);
		seat.setShow(show);
		seat.setSeatNumber("A1");
		seat.setStatus(SeatStatus.BOOKED);

		Booking booking = new Booking(new User(), show, Set.of(seat));
		when(bookingRepository.findByIdAndUserId(BOOKING_ID, USER_ID)).thenReturn(Optional.of(booking));
		return booking;
	}

	private static Show show(String startTime) {
		Venue venue = new Venue();
		venue.setName("Test Venue");
		Show show = new Show();
		show.setId(1L);
		show.setTitle("Test Show");
		show.setStartTime(Instant.parse(startTime));
		show.setPrice(new BigDecimal("250.00"));
		show.setVenue(venue);
		return show;
	}
}

package com.abhishekraj.BookMySeat.ticket;

import com.abhishekraj.BookMySeat.dto.CheckInResponse;
import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.exception.CheckInRefusedException;
import com.abhishekraj.BookMySeat.exception.CheckInRefusedException.Reason;
import com.abhishekraj.BookMySeat.exception.InvalidTicketException;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

/**
 * Lets a ticket in once. The check-in is one conditional UPDATE ("... where not yet checked in and still
 * confirmed"), so two scanners reading the same ticket at the same moment can't both let it in.
 */
@Slf4j
@Service
public class CheckInService {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a, d MMM", Locale.ENGLISH)
			.withZone(ZoneId.of("Asia/Kolkata"));

	private final TicketCodes codes;
	private final BookingRepository bookingRepository;
	private final EntityManager entityManager;
	private final MeterRegistry meterRegistry;
	private final Clock clock;
	private final Duration opensBefore;
	private final Duration closesAfter;

	public CheckInService(TicketCodes codes, BookingRepository bookingRepository, EntityManager entityManager,
			MeterRegistry meterRegistry, Clock clock,
			@Value("${tickets.check-in.opens-before}") Duration opensBefore,
			@Value("${tickets.check-in.closes-after}") Duration closesAfter) {
		this.codes = codes;
		this.bookingRepository = bookingRepository;
		this.entityManager = entityManager;
		this.meterRegistry = meterRegistry;
		this.clock = clock;
		this.opensBefore = opensBefore;
		this.closesAfter = closesAfter;
	}

	@Transactional
	public CheckInResponse checkIn(Long showId, String code, Long staffUserId) {
		Long bookingId = codes.bookingIdOf(code).orElseThrow(() -> refusedInvalid());
		Booking booking = bookingRepository.findById(bookingId).orElseThrow(() -> refusedInvalid());
		Show show = booking.getShow();
		Instant now = Instant.now(clock);

		if (!show.getId().equals(showId)) {
			throw refused(Reason.OTHER_SHOW, "This ticket is for " + show.getTitle() + " at " + TIME.format(show.getStartTime())
					+ ", not this show", null);
		}
		if (booking.getStatus() != BookingStatus.CONFIRMED) {
			throw refused(Reason.CANCELLED, "This booking was cancelled", null);
		}
		if (booking.getCheckedInAt() != null) {
			throw refused(Reason.ALREADY_CHECKED_IN, "Already checked in at " + TIME.format(booking.getCheckedInAt()),
					booking.getCheckedInAt());
		}
		if (now.isBefore(show.getStartTime().minus(opensBefore))) {
			throw refused(Reason.TOO_EARLY, "Check-in opens " + opensBefore.toHours() + " hours before the show", null);
		}
		if (now.isAfter(show.getStartTime().plus(closesAfter))) {
			throw refused(Reason.TOO_LATE, "Check-in for this show has closed", null);
		}

		// The decisive step: only one caller can turn checked_in_at from null into a time
		if (bookingRepository.markCheckedIn(bookingId, now, staffUserId) == 0) {
			entityManager.refresh(booking); // someone else got there first (or it was cancelled just now)
			if (booking.getStatus() != BookingStatus.CONFIRMED) {
				throw refused(Reason.CANCELLED, "This booking was cancelled", null);
			}
			throw refused(Reason.ALREADY_CHECKED_IN, "Already checked in at " + TIME.format(booking.getCheckedInAt()),
					booking.getCheckedInAt());
		}
		meterRegistry.counter("checkins", "result", "ok").increment();
		log.info("Booking {} checked in for show {}", bookingId, showId);
		return new CheckInResponse(bookingId, booking.getUser().getName(),
				booking.getSeats().stream().sorted(Comparator.comparing(Seat::getId)).map(Seat::getSeatNumber).toList(),
				show.getTitle(), now);
	}

	private InvalidTicketException refusedInvalid() {
		meterRegistry.counter("checkins", "result", "invalid").increment();
		return new InvalidTicketException();
	}

	private CheckInRefusedException refused(Reason reason, String message, Instant checkedInAt) {
		meterRegistry.counter("checkins", "result", reason.name().toLowerCase(Locale.ROOT)).increment();
		return new CheckInRefusedException(reason, message, checkedInAt);
	}
}

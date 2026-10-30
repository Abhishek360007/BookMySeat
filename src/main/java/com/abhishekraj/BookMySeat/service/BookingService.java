package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.BookingResponse;
import com.abhishekraj.BookMySeat.dto.ConfirmBookingRequest;
import com.abhishekraj.BookMySeat.dto.HoldRequest;
import com.abhishekraj.BookMySeat.dto.HoldResponse;
import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatStatus;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class BookingService {

	private final BookingRepository bookingRepository;
	private final SeatRepository seatRepository;
	private final ShowRepository showRepository;
	private final UserRepository userRepository;
	private final SeatHoldService seatHoldService;
	private final Clock clock;
	private final long cancelCutoffMinutes;

	// Business metrics at /actuator/metrics. Tags stay low-cardinality: never user or seat ids.
	private final Counter bookingsConfirmed;
	private final Counter bookingsCancelled;
	private final Counter conflictHoldMissing;
	private final Counter conflictAlreadyBooked;
	private final Counter holdSuccess;
	private final Counter holdConflict;

	public BookingService(BookingRepository bookingRepository, SeatRepository seatRepository,
			ShowRepository showRepository, UserRepository userRepository, SeatHoldService seatHoldService,
			Clock clock, @Value("${booking.cancel-cutoff-minutes}") long cancelCutoffMinutes,
			MeterRegistry meterRegistry) {
		this.bookingRepository = bookingRepository;
		this.seatRepository = seatRepository;
		this.showRepository = showRepository;
		this.userRepository = userRepository;
		this.seatHoldService = seatHoldService;
		this.clock = clock;
		this.cancelCutoffMinutes = cancelCutoffMinutes;
		this.bookingsConfirmed = meterRegistry.counter("bookings.confirmed");
		this.bookingsCancelled = meterRegistry.counter("bookings.cancelled");
		this.conflictHoldMissing = meterRegistry.counter("booking.conflicts", "reason", "hold_missing");
		this.conflictAlreadyBooked = meterRegistry.counter("booking.conflicts", "reason", "already_booked");
		this.holdSuccess = meterRegistry.counter("seat.holds", "result", "success");
		this.holdConflict = meterRegistry.counter("seat.holds", "result", "conflict");
	}

	@Transactional(readOnly = true)
	public HoldResponse holdSeats(Long userId, HoldRequest request) {
		Show show = findShow(request.showId());
		requireNotStarted(show);
		List<Seat> seats = loadSeatsForShow(show.getId(), request.seatIds());

		for (Seat seat : seats) {
			if (seat.getStatus() == SeatStatus.BOOKED) {
				conflictAlreadyBooked.increment();
				throw new ConflictException("Seat " + seat.getSeatNumber() + " is already booked");
			}
		}

		List<Long> seatIds = seats.stream().map(Seat::getId).toList();
		if (!seatHoldService.holdAll(request.showId(), seatIds, userId)) {
			holdConflict.increment();
			throw new ConflictException("One or more seats are being held by someone else. Please choose different seats.");
		}
		holdSuccess.increment();

		return new HoldResponse(request.showId(), seatIds,
				seats.stream().map(Seat::getSeatNumber).toList(),
				seatHoldService.newExpiryTime());
	}

	public void releaseSeats(Long userId, HoldRequest request) {
		seatHoldService.releaseAll(request.showId(), request.seatIds().stream().distinct().toList(), userId);
	}

	@Transactional
	public BookingResponse confirm(Long userId, ConfirmBookingRequest request) {
		Set<Long> seatIds = new LinkedHashSet<>(request.seatIds());
		Show show = findShow(request.showId());
		requireNotStarted(show);
		List<Seat> seats = loadSeatsForShow(show.getId(), request.seatIds());

		if (!seatHoldService.isHeldBy(show.getId(), seatIds, userId)) {
			conflictHoldMissing.increment();
			throw new ConflictException("Your hold has expired or these seats aren't held by you. Hold them again.");
		}

		for (Seat seat : seats) {
			if (seat.getStatus() != SeatStatus.AVAILABLE) {
				conflictAlreadyBooked.increment();
				throw new ConflictException("Seat " + seat.getSeatNumber() + " is already booked");
			}
			seat.setStatus(SeatStatus.BOOKED); // @Version is checked and bumped on commit
		}

		Booking booking = bookingRepository.save(
				new Booking(userRepository.getReferenceById(userId), show, new HashSet<>(seats)));

		// Release the Redis holds only if the database commit succeeds
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				seatHoldService.releaseAll(show.getId(), seatIds, userId);
			}
		});

		bookingsConfirmed.increment();
		return BookingResponse.from(booking);
	}

	@Transactional
	public BookingResponse cancel(Long userId, Long bookingId) {
		// Someone else's booking is a 404, not a 403, so booking ids don't leak
		Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found with id " + bookingId));
		if (booking.getStatus() == BookingStatus.CANCELLED) {
			throw new ConflictException("Booking " + bookingId + " is already cancelled");
		}
		// Allowed exactly at the cutoff, refused one second after
		Instant cutoff = booking.getShow().getStartTime().minus(Duration.ofMinutes(cancelCutoffMinutes));
		if (Instant.now(clock).isAfter(cutoff)) {
			throw new ConflictException("Cancellations close " + cancelCutoffMinutes
					+ " minutes before the show starts");
		}
		booking.cancel();
		bookingsCancelled.increment();
		return BookingResponse.from(booking);
	}

	// Replaying an idempotent confirm returns the booking as it is now
	@Transactional(readOnly = true)
	public BookingResponse getBooking(Long userId, Long bookingId) {
		return bookingRepository.findByIdAndUserId(bookingId, userId)
				.map(BookingResponse::from)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found with id " + bookingId));
	}

	@Transactional(readOnly = true)
	public List<BookingResponse> myBookings(Long userId) {
		return bookingRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
				.map(BookingResponse::from).toList();
	}

	private Show findShow(Long showId) {
		return showRepository.findById(showId)
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + showId));
	}

	private void requireNotStarted(Show show) {
		if (!Instant.now(clock).isBefore(show.getStartTime())) {
			throw new ConflictException("Show " + show.getId() + " has already started");
		}
	}

	private List<Seat> loadSeatsForShow(Long showId, List<Long> requestedIds) {
		List<Long> distinctIds = requestedIds.stream().distinct().toList();
		List<Seat> seats = seatRepository.findAllById(distinctIds);

		boolean allFound = seats.size() == distinctIds.size();
		boolean allInShow = seats.stream().allMatch(seat -> seat.getShow().getId().equals(showId));
		if (!allFound || !allInShow) {
			throw new ResourceNotFoundException("One or more seats were not found for show " + showId);
		}

		seats.sort(Comparator.comparing(Seat::getId));
		return seats;
	}
}

package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.BestSeatsResponse;
import com.abhishekraj.BookMySeat.dto.HoldRequest;
import com.abhishekraj.BookMySeat.dto.HoldResponse;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatCategory;
import com.abhishekraj.BookMySeat.entity.SeatStatus;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.service.BestSeatFinder.SeatSpot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * "Pick the best seats for me": BestSeatFinder on a show's live seat map (booked seats and anyone's holds are not
 * free), and holding what it picks in one call.
 */
@Slf4j
@Service
public class BestSeatService {

	/** Enough for a crowd asking at once: each failed try means someone else got that block, so there is one less. */
	static final int HOLD_ATTEMPTS = 10;

	private static final Pattern SEAT_NUMBER = Pattern.compile("^([A-Z]+)(\\d+)$");

	private final ShowRepository showRepository;
	private final SeatRepository seatRepository;
	private final SeatHoldService seatHoldService;
	private final BookingService bookingService;
	private final Clock clock;

	public BestSeatService(ShowRepository showRepository, SeatRepository seatRepository,
			SeatHoldService seatHoldService, BookingService bookingService, Clock clock) {
		this.showRepository = showRepository;
		this.seatRepository = seatRepository;
		this.seatHoldService = seatHoldService;
		this.bookingService = bookingService;
		this.clock = clock;
	}

	/** The best `count` free seats together right now, without holding them. */
	@Transactional(readOnly = true)
	public BestSeatsResponse suggest(Long showId, int count, SeatCategory category) {
		SeatMap map = load(showId);
		List<Long> picked = map.pick(seatHoldService.findHeldSeatIds(showId, map.ids()), count, category, Set.of())
				.orElseThrow(() -> noBlock(count, category));
		return map.describe(picked);
	}

	/**
	 * Picks and holds the best seats for this user. If someone else holds the picked block first (the hold is all or
	 * nothing), the next best block is tried with the holds read again.
	 */
	public HoldResponse holdBest(Long userId, Long showId, int count, SeatCategory category) {
		SeatMap map = load(showId);
		Set<Long> lost = new HashSet<>(); // blocks someone else got between our read and our hold
		for (int attempt = 1; attempt <= HOLD_ATTEMPTS; attempt++) {
			Set<Long> held = seatHoldService.findHeldSeatIds(showId, map.ids());
			List<Long> picked = map.pick(held, count, category, lost).orElseThrow(() -> noBlock(count, category));
			try {
				return bookingService.holdSeats(userId, new HoldRequest(showId, picked));
			} catch (ConflictException refused) {
				// Retry only when someone else got (some of) these seats; anything else (show cancelled meanwhile) stands
				boolean taken = !seatHoldService.findHeldSeatIds(showId, picked).isEmpty()
						|| seatRepository.findAllById(picked).stream().anyMatch(seat -> seat.getStatus() != SeatStatus.AVAILABLE);
				if (!taken) {
					throw refused;
				}
				log.debug("Best seats {} for show {} taken first (attempt {})", picked, showId, attempt);
				lost.addAll(picked);
			}
		}
		throw new ConflictException("Seats are going fast: couldn't hold " + count + " seats together. Please try again.");
	}

	private SeatMap load(Long showId) {
		Show show = showRepository.findWithPricesById(showId)
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + showId));
		if (show.isCancelled()) {
			throw new ConflictException("Show " + showId + " has been cancelled");
		}
		if (!Instant.now(clock).isBefore(show.getStartTime())) {
			throw new ConflictException("Show " + showId + " has already started");
		}
		return new SeatMap(show, seatRepository.findByShowIdOrderByIdAsc(showId));
	}

	private static ConflictException noBlock(int count, SeatCategory category) {
		String what = count == 1 ? "No seat is" : "No " + count + " seats together are";
		String where = category == null ? "" : " in " + category.name().toLowerCase(Locale.ROOT);
		return new ConflictException(what + " free" + where + " right now");
	}

	/** One show's seats, with what the finder needs. */
	private record SeatMap(Show show, List<Seat> seats) {

		List<Long> ids() {
			return seats.stream().map(Seat::getId).toList();
		}

		Optional<List<Long>> pick(Set<Long> held, int count, SeatCategory category, Set<Long> lost) {
			List<SeatSpot> spots = seats.stream().map(seat -> {
				Matcher m = SEAT_NUMBER.matcher(seat.getSeatNumber());
				if (!m.matches()) {
					throw new IllegalStateException("Unexpected seat number " + seat.getSeatNumber());
				}
				boolean free = seat.getStatus() == SeatStatus.AVAILABLE && !held.contains(seat.getId())
						&& !lost.contains(seat.getId());
				return new SeatSpot(seat.getId(), m.group(1), Integer.parseInt(m.group(2)), seat.getCategory(), free);
			}).toList();
			return BestSeatFinder.find(spots, Set.copyOf(show.aisleAfterList()), count, category);
		}

		BestSeatsResponse describe(List<Long> picked) {
			Map<Long, Seat> byId = seats.stream().collect(Collectors.toMap(Seat::getId, Function.identity()));
			List<Seat> chosen = picked.stream().map(byId::get).toList();
			BigDecimal total = show.totalFor(chosen);
			return new BestSeatsResponse(show.getId(), picked, chosen.stream().map(Seat::getSeatNumber).toList(),
					chosen.getFirst().getCategory(), total);
		}
	}
}

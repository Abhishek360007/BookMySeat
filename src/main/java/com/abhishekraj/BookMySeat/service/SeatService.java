package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.GenerateSeatsRequest;
import com.abhishekraj.BookMySeat.dto.SeatResponse;
import com.abhishekraj.BookMySeat.config.CacheConfig;
import com.abhishekraj.BookMySeat.dto.SeatLayoutRequest;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatCategory;
import com.abhishekraj.BookMySeat.entity.SeatStatus;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SeatService {

	private final SeatRepository seatRepository;
	private final ShowRepository showRepository;
	private final SeatHoldService seatHoldService;

	@Transactional
	public List<SeatResponse> generateSeats(Long showId, GenerateSeatsRequest request) {
		Show show = findShow(showId);
		if (seatRepository.existsByShowId(showId)) {
			throw new ConflictException("Seats already exist for show " + showId);
		}

		List<Seat> seats = new ArrayList<>();
		for (int row = 0; row < request.rows(); row++) {
			char rowLabel = (char) ('A' + row);
			SeatCategory category = categoryOfRow(row, request.rows(), request.premium(), request.recliner());
			for (int number = 1; number <= request.seatsPerRow(); number++) {
				Seat seat = new Seat();
				seat.setShow(show);
				seat.setSeatNumber(rowLabel + String.valueOf(number));
				seat.setCategory(category);
				seats.add(seat);
			}
		}

		return seatRepository.saveAll(seats).stream().map(SeatResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public List<SeatResponse> getSeats(Long showId) {
		Show show = findShow(showId);
		List<Seat> seats = seatRepository.findByShowIdOrderByIdAsc(showId);
		Set<Long> held = seatHoldService.findHeldSeatIds(showId, seats.stream().map(Seat::getId).toList());

		return seats.stream().map(seat -> {
			String status;
			if (seat.getStatus() == SeatStatus.BOOKED) {
				status = "BOOKED";
			} else if (held.contains(seat.getId())) {
				status = "HELD";
			} else {
				status = "AVAILABLE";
			}
			return new SeatResponse(seat.getId(), seat.getSeatNumber(), status, seat.getCategory(), show.priceOf(seat.getCategory()));
		}).toList();
	}

	/**
	 * Re-assigns rows to categories on an existing seat map. Safe at any time: bookings keep the total they were
	 * made with, and a checkout in progress keeps the amount it is paying. New holds use the new prices.
	 */
	@CacheEvict(cacheNames = CacheConfig.SHOW, key = "#showId")
	@Transactional
	public List<SeatResponse> applyLayout(Long showId, SeatLayoutRequest request) {
		Show show = findShow(showId);
		if (show.isCancelled()) {
			throw new ConflictException("Show " + showId + " is cancelled");
		}
		List<Seat> seats = seatRepository.findByShowIdOrderByIdAsc(showId);
		List<String> rows = seats.stream().map(seat -> rowOf(seat.getSeatNumber())).distinct().sorted().toList();
		if (rows.isEmpty()) {
			throw new ConflictException("Show " + showId + " has no seats yet");
		}
		if (request.premiumRows() + request.reclinerRows() > rows.size()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"premiumRows + reclinerRows can't be more than the " + rows.size() + " rows of this show");
		}
		for (Seat seat : seats) {
			int row = rows.indexOf(rowOf(seat.getSeatNumber()));
			seat.setCategory(categoryOfRow(row, rows.size(), request.premiumRows(), request.reclinerRows()));
		}
		return getSeats(showId);
	}

	private static String rowOf(String seatNumber) {
		return seatNumber.replaceAll("\\d+$", "");
	}

	/** Counted from the back (row index rows-1): recliners last, premium in front of them. */
	static SeatCategory categoryOfRow(int row, int rows, int premiumRows, int reclinerRows) {
		int fromBack = rows - 1 - row;
		if (fromBack < reclinerRows) {
			return SeatCategory.RECLINER;
		}
		return fromBack < reclinerRows + premiumRows ? SeatCategory.PREMIUM : SeatCategory.REGULAR;
	}

	private Show findShow(Long showId) {
		return showRepository.findById(showId)
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + showId));
	}
}

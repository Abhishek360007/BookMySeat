package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.GenerateSeatsRequest;
import com.abhishekraj.BookMySeat.dto.SeatResponse;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatStatus;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import lombok.RequiredArgsConstructor;
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
			for (int number = 1; number <= request.seatsPerRow(); number++) {
				Seat seat = new Seat();
				seat.setShow(show);
				seat.setSeatNumber(rowLabel + String.valueOf(number));
				seats.add(seat);
			}
		}

		return seatRepository.saveAll(seats).stream().map(SeatResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public List<SeatResponse> getSeats(Long showId) {
		findShow(showId);
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
			return new SeatResponse(seat.getId(), seat.getSeatNumber(), status);
		}).toList();
	}

	private Show findShow(Long showId) {
		return showRepository.findById(showId)
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + showId));
	}
}

package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.GenerateSeatsRequest;
import com.abhishekraj.BookMySeat.dto.SeatResponse;
import com.abhishekraj.BookMySeat.entity.Seat;
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

@Service
@RequiredArgsConstructor
public class SeatService {

	private final SeatRepository seatRepository;
	private final ShowRepository showRepository;

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
		return seatRepository.findByShowIdOrderByIdAsc(showId).stream()
				.map(SeatResponse::from).toList();
	}

	private Show findShow(Long showId) {
		return showRepository.findById(showId)
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + showId));
	}
}

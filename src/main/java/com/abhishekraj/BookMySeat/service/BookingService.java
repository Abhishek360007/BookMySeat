package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.HoldRequest;
import com.abhishekraj.BookMySeat.dto.HoldResponse;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatStatus;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingService {

	private final SeatRepository seatRepository;
	private final SeatHoldService seatHoldService;

	@Transactional(readOnly = true)
	public HoldResponse holdSeats(Long userId, HoldRequest request) {
		List<Seat> seats = loadSeatsForShow(request.showId(), request.seatIds());

		for (Seat seat : seats) {
			if (seat.getStatus() == SeatStatus.BOOKED) {
				throw new ConflictException("Seat " + seat.getSeatNumber() + " is already booked");
			}
		}

		List<Long> seatIds = seats.stream().map(Seat::getId).toList();
		if (!seatHoldService.holdAll(request.showId(), seatIds, userId)) {
			throw new ConflictException("One or more seats are being held by someone else. Please choose different seats.");
		}

		return new HoldResponse(request.showId(), seatIds,
				seats.stream().map(Seat::getSeatNumber).toList(),
				seatHoldService.newExpiryTime());
	}

	public void releaseSeats(Long userId, HoldRequest request) {
		seatHoldService.releaseAll(request.showId(), request.seatIds().stream().distinct().toList(), userId);
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

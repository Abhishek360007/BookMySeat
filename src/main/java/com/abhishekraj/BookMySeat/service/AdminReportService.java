package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.AdminBookingResponse;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.dto.SeatResponse;
import com.abhishekraj.BookMySeat.dto.ShowSummaryResponse;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminReportService {

	private final BookingRepository bookingRepository;
	private final ShowRepository showRepository;
	private final SeatService seatService;

	@Transactional(readOnly = true)
	public PageResponse<AdminBookingResponse> bookings(Long showId, BookingStatus status, Pageable pageable) {
		requireShow(showId);
		// Seats are loaded lazily in one batched IN (...) query per page (default_batch_fetch_size)
		return PageResponse.of(bookingRepository.findByShowIdAndStatus(showId, status, pageable),
				AdminBookingResponse::from);
	}

	@Transactional(readOnly = true)
	public ShowSummaryResponse summary(Long showId) {
		requireShow(showId);
		// Same seat map as GET /seats, so Redis stays the only source of truth for holds
		List<SeatResponse> seats = seatService.getSeats(showId);
		Map<String, Long> byStatus = seats.stream()
				.collect(Collectors.groupingBy(SeatResponse::status, Collectors.counting()));
		long booked = byStatus.getOrDefault("BOOKED", 0L);
		long held = byStatus.getOrDefault("HELD", 0L);

		return new ShowSummaryResponse(showId, seats.size(), booked, held, seats.size() - booked - held,
				bookingRepository.countByShowIdAndStatus(showId, BookingStatus.CONFIRMED),
				bookingRepository.sumTotal(showId, BookingStatus.CONFIRMED).setScale(2)); // 0 -> 0.00 when nothing is booked
	}

	private void requireShow(Long showId) {
		if (!showRepository.existsById(showId)) {
			throw new ResourceNotFoundException("Show not found with id " + showId);
		}
	}
}

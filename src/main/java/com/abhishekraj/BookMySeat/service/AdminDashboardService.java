package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.AdminOverviewResponse;
import com.abhishekraj.BookMySeat.dto.AdminShowResponse;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.ShowStatus;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.ShowSpecifications;
import com.abhishekraj.BookMySeat.repository.ShowStats;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

	public enum When { UPCOMING, PAST, ALL }

	private final ShowRepository showRepository;
	private final SeatRepository seatRepository;
	private final BookingRepository bookingRepository;
	private final Clock clock;

	/**
	 * A page of shows with their sales. Always three queries (shows + count, seats, bookings) whatever the
	 * page size: the seat and booking numbers come from one GROUP BY query each for the whole page.
	 */
	@Transactional(readOnly = true)
	public PageResponse<AdminShowResponse> shows(When when, String text, Pageable pageable) {
		Instant now = Instant.now(clock);
		List<Specification<Show>> filters = new ArrayList<>();
		if (when == When.UPCOMING) {
			filters.add(ShowSpecifications.startsAtOrAfter(now));
			filters.add(ShowSpecifications.scheduled()); // cancelled shows are listed under ALL
		}
		if (when == When.PAST) filters.add(ShowSpecifications.startsBefore(now));
		if (text != null && !text.isBlank()) filters.add(ShowSpecifications.titleContains(text));

		Page<Show> page = showRepository.findAll(Specification.allOf(filters), pageable);
		List<Long> ids = page.getContent().stream().map(Show::getId).toList();
		if (ids.isEmpty()) {
			return PageResponse.of(page, show -> null);
		}
		Map<Long, ShowStats.Seats> seats = seatRepository.countByShow(ids).stream()
				.collect(Collectors.toMap(ShowStats.Seats::showId, Function.identity()));
		Map<Long, ShowStats.Sales> sales = bookingRepository.salesByShow(ids, BookingStatus.CONFIRMED).stream()
				.collect(Collectors.toMap(ShowStats.Sales::showId, Function.identity()));

		return PageResponse.of(page, show -> {
			ShowStats.Seats s = seats.getOrDefault(show.getId(), new ShowStats.Seats(show.getId(), 0, 0));
			ShowStats.Sales b = sales.getOrDefault(show.getId(), new ShowStats.Sales(show.getId(), 0, BigDecimal.ZERO));
			return new AdminShowResponse(show.getId(), show.getTitle(), show.getStartTime(), show.getPrice(),
					show.getVenue().getId(), show.getVenue().getName(), show.getVenue().getCity(), show.getStatus(),
					s.total(), s.booked(), b.bookings(), b.revenue().setScale(2));
		});
	}

	@Transactional(readOnly = true)
	public AdminOverviewResponse overview() {
		Instant now = Instant.now(clock);
		Instant weekAgo = now.minus(Duration.ofDays(7));
		ShowStats.SeatTotals upcomingSeats = seatRepository.countUpcoming(now);
		return new AdminOverviewResponse(
				showRepository.countByStatusAndStartTimeAfter(ShowStatus.SCHEDULED, now),
				bookingRepository.countByStatus(BookingStatus.CONFIRMED),
				bookingRepository.sumTotalByStatus(BookingStatus.CONFIRMED).setScale(2),
				bookingRepository.countByStatusAndCreatedAtAfter(BookingStatus.CONFIRMED, weekAgo),
				bookingRepository.sumTotalByStatusAndCreatedAtAfter(BookingStatus.CONFIRMED, weekAgo).setScale(2),
				upcomingSeats.total(), upcomingSeats.booked());
	}
}

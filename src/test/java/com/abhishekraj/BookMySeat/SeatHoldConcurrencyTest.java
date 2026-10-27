package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.Venue;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.abhishekraj.BookMySeat.service.SeatHoldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// No @Transactional: every thread must run against real, committed data
@SpringBootTest
@Import(TestcontainersConfig.class)
class SeatHoldConcurrencyTest {

	@Autowired SeatHoldService holds;
	@Autowired VenueRepository venueRepository;
	@Autowired ShowRepository showRepository;
	@Autowired SeatRepository seatRepository;

	@AfterEach
	void cleanUp() {
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
	}

	@Test
	void fiftyUsersOneSeat_exactlyOneHoldWins() throws Exception {
		Seat seat = TestData.seat(seatRepository, showRepository, venueRepository);
		Long showId = seat.getShow().getId();
		Long seatId = seat.getId();

		int n = 50;
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch ready = new CountDownLatch(n);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger ok = new AtomicInteger();
		AtomicInteger conflict = new AtomicInteger();

		List<Future<Object>> futures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			long userId = 1000 + i; // holds don't need real users
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await(); // everyone starts together
				if (holds.holdAll(showId, List.of(seatId), userId)) {
					ok.incrementAndGet();
				} else {
					conflict.incrementAndGet();
				}
				return null;
			}));
		}
		ready.await();
		go.countDown();
		for (Future<Object> future : futures) {
			future.get(30, TimeUnit.SECONDS); // surfaces unexpected errors
		}
		pool.shutdown();

		assertThat(ok.get()).isEqualTo(1);
		assertThat(conflict.get()).isEqualTo(n - 1);
	}

	/** Shared test-data helpers. */
	static final class TestData {

		static Seat seat(SeatRepository seats, ShowRepository shows, VenueRepository venues) {
			Venue venue = new Venue();
			venue.setName("Test Venue");
			venue.setCity("Test City");
			venues.save(venue);

			Show show = new Show();
			show.setTitle("Test Show");
			show.setStartTime(Instant.now().plus(1, ChronoUnit.DAYS));
			show.setPrice(new BigDecimal("100.00"));
			show.setVenue(venue);
			shows.save(show);

			Seat seat = new Seat();
			seat.setShow(show);
			seat.setSeatNumber("A1");
			return seats.save(seat);
		}
	}
}

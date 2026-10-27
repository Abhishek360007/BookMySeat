package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.dto.ConfirmBookingRequest;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatStatus;
import com.abhishekraj.BookMySeat.entity.User;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.repository.*;
import com.abhishekraj.BookMySeat.service.BookingService;
import com.abhishekraj.BookMySeat.service.SeatHoldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// No @Transactional: every thread needs its own real transaction that commits.
// Pool of 60 so all 50 threads hold a connection at once instead of queueing.
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=60")
@Import(TestcontainersConfig.class)
class BookingConcurrencyTest {

	@Autowired BookingService bookingService;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@MockitoBean SeatHoldService holds;

	@AfterEach
	void cleanUp() {
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void fiftyConfirmsOneSeat_exactlyOneBooking() throws Exception {
		when(holds.isHeldBy(any(), any(), any())).thenReturn(true); // everyone "holds" it, so only @Version decides

		Seat testSeat = SeatHoldConcurrencyTest.TestData.seat(seatRepository, showRepository, venueRepository);
		Long showId = testSeat.getShow().getId();
		Long seatId = testSeat.getId();
		List<Long> userIds = saveUsers(50);

		int n = userIds.size();
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch ready = new CountDownLatch(n);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger ok = new AtomicInteger();
		AtomicInteger alreadyBooked = new AtomicInteger(); // read the seat after the winner committed
		AtomicInteger lockLost = new AtomicInteger();      // read AVAILABLE, then lost the @Version race

		List<Future<Object>> futures = new ArrayList<>();
		for (Long userId : userIds) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				try {
					bookingService.confirm(userId, new ConfirmBookingRequest(showId, List.of(seatId)));
					ok.incrementAndGet();
				} catch (ConflictException e) {
					alreadyBooked.incrementAndGet();
				} catch (OptimisticLockingFailureException e) {
					lockLost.incrementAndGet();
				}
				return null;
			}));
		}
		ready.await();
		go.countDown();
		for (Future<Object> future : futures) {
			future.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();

		System.out.printf("ok=%d alreadyBooked=%d lockLost=%d%n", ok.get(), alreadyBooked.get(), lockLost.get());

		assertThat(ok.get()).isEqualTo(1);
		assertThat(alreadyBooked.get() + lockLost.get()).isEqualTo(n - 1);
		assertThat(bookingRepository.count()).isEqualTo(1);
		Seat seat = seatRepository.findById(seatId).orElseThrow();
		assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
		assertThat(seat.getVersion()).isEqualTo(1L);
	}

	private List<Long> saveUsers(int count) {
		List<User> users = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			User user = new User();
			user.setName("User " + i);
			user.setEmail("user" + i + "@test.local");
			user.setPassword("not-a-real-hash");
			user.setRole(Role.USER);
			users.add(user);
		}
		return userRepository.saveAll(users).stream().map(User::getId).toList();
	}
}

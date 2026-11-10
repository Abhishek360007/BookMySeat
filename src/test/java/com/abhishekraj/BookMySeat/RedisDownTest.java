package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatStatus;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.User;
import com.abhishekraj.BookMySeat.entity.Venue;
import com.abhishekraj.BookMySeat.entity.WaitlistEntry;
import com.abhishekraj.BookMySeat.entity.WaitlistStatus;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.FilmRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.abhishekraj.BookMySeat.repository.WaitlistRepository;
import com.abhishekraj.BookMySeat.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Redis points at a port where nothing listens: the cache and the rate limiter must degrade, not fail requests.
// (This happened in production: the first Upstash connection after a cold start timed out and search returned 500.)
@SpringBootTest(properties = {
		"spring.data.redis.host=localhost",
		"spring.data.redis.port=1",
		"spring.data.redis.timeout=500ms"
})
@AutoConfigureMockMvc
@Import(RedisDownTest.PostgresOnly.class)
class RedisDownTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class PostgresOnly {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
		}
	}

	@Autowired MockMvc mvc;
	@Autowired ShowRepository showRepository;
	@Autowired FilmRepository filmRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired UserRepository userRepository;
	@Autowired BookingRepository bookingRepository;
	@Autowired WaitlistRepository waitlistRepository;
	@Autowired BookingService bookingService;

	@Test
	void cachedReadsFallBackToTheDatabase() throws Exception {
		Venue venue = new Venue();
		venue.setName("No Redis Venue");
		venue.setCity("Offline");
		venue = venueRepository.save(venue);
		Show show = new Show();
		show.setTitle("Still Works");
		show.setFilm(TestFilms.named(filmRepository, "Still Works"));
		show.setVenue(venue);
		show.setStartTime(Instant.now().plus(1, ChronoUnit.DAYS));
		show.setPrice(new BigDecimal("250.00"));
		show = showRepository.save(show);

		mvc.perform(get("/api/shows/search?city=offline"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].title").value("Still Works"));
		mvc.perform(get("/api/shows/" + show.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Still Works"));
	}

	@Test
	void aCustomerCanStillCancel_theWaitlistOfferWaitsForRedis() {
		Venue venue = new Venue();
		venue.setName("No Redis Venue 2");
		venue.setCity("Offline");
		venue = venueRepository.save(venue);
		Show show = new Show();
		show.setTitle("Sold Out Offline");
		show.setFilm(TestFilms.named(filmRepository, "Sold Out Offline"));
		show.setVenue(venue);
		show.setStartTime(Instant.now().plus(1, ChronoUnit.DAYS));
		show.setPrice(new BigDecimal("250.00"));
		show = showRepository.save(show);
		Seat seat = new Seat();
		seat.setShow(show);
		seat.setSeatNumber("A1");
		seat.setStatus(SeatStatus.BOOKED);
		seat = seatRepository.save(seat);
		User buyer = userRepository.save(user("redis-down-buyer@example.com"));
		User waiting = userRepository.save(user("redis-down-waiting@example.com"));
		Booking booking = bookingRepository.save(new Booking(buyer, show, new HashSet<>(Set.of(seat))));
		waitlistRepository.save(new WaitlistEntry(show, waiting, 1, Instant.now()));

		assertThat(bookingService.cancel(buyer.getId(), booking.getId()).status()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(waitlistRepository.findAll()).singleElement()
				.satisfies(entry -> assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.WAITING));
	}

	private static User user(String email) {
		User user = new User();
		user.setName("Offline");
		user.setEmail(email);
		user.setPassword("not-a-real-hash");
		user.setRole(Role.USER);
		return user;
	}

	@Test
	void rateLimiterFailsOpen() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}"))
				.andExpect(status().isUnauthorized()); // not 429 and not 500
	}
}

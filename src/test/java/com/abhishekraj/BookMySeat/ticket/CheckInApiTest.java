package com.abhishekraj.BookMySeat.ticket;

import com.abhishekraj.BookMySeat.TestcontainersConfig;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Checking tickets in at the door. Same configuration as BookingApiTest (shared Spring context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CheckInApiTest {

	@Autowired MockMvc mvc;
	@Autowired TicketCodes codes;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired JdbcTemplate jdbc;

	String admin;
	String user;
	long showId;
	long otherShowId;
	List<Integer> seats;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		// 3 hours away: inside the check-in window, and still before the 2-hour cancellation cutoff
		showId = createShowWithSeats("Door Show", 3);
		otherShowId = createShowWithSeats("Other Show", 3);
		seats = seatIds(showId);
		user = registerAndLogin("door-guest@example.com");
	}

	@AfterEach
	void cleanUp() {
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
	}

	@Test
	void aTicket_getsInOnce() throws Exception {
		int bookingId = book(showId, seats.get(0), seats.get(1));
		String code = codes.codeFor(bookingId);

		checkIn(showId, code).andExpect(status().isOk())
				.andExpect(jsonPath("$.bookingId").value(bookingId))
				.andExpect(jsonPath("$.holderName").value("Door Guest"))
				.andExpect(jsonPath("$.seats[0]").value("A1"))
				.andExpect(jsonPath("$.seats[1]").value("A2"))
				.andExpect(jsonPath("$.showTitle").value("Door Show"))
				.andExpect(jsonPath("$.checkedInAt").isNotEmpty());

		checkIn(showId, code).andExpect(status().isConflict())
				.andExpect(jsonPath("$.reason").value("ALREADY_CHECKED_IN"))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Already checked in at ")))
				.andExpect(jsonPath("$.checkedInAt").isNotEmpty());

		mvc.perform(get("/api/admin/shows/" + showId + "/summary").header("Authorization", "Bearer " + admin))
				.andExpect(jsonPath("$.checkedInBookings").value(1));
		mvc.perform(get("/api/admin/shows/" + showId + "/bookings").header("Authorization", "Bearer " + admin))
				.andExpect(jsonPath("$.content[0].checkedInAt").isNotEmpty());
	}

	@Test
	void forgedOrUnknownTickets_are400() throws Exception {
		int bookingId = book(showId, seats.get(0));
		String code = codes.codeFor(bookingId);

		checkIn(showId, code.substring(0, code.length() - 1) + (code.endsWith("A") ? "B" : "A"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID"));
		checkIn(showId, "hello").andExpect(status().isBadRequest());
		checkIn(showId, codes.codeFor(99_999_999)).andExpect(status().isBadRequest()); // genuine format, no booking
	}

	@Test
	void ticketsForAnotherShow_orCancelled_areRefused() throws Exception {
		int other = book(otherShowId, seatIds(otherShowId).get(0));
		checkIn(showId, codes.codeFor(other)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.reason").value("OTHER_SHOW"))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("This ticket is for Other Show at ")));

		int cancelled = book(showId, seats.get(0));
		mvc.perform(post("/api/bookings/" + cancelled + "/cancel").header("Authorization", "Bearer " + user))
				.andExpect(status().isOk());
		checkIn(showId, codes.codeFor(cancelled)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.reason").value("CANCELLED"));
	}

	@Test
	void outsideTheDoorWindow_isRefused() throws Exception {
		int bookingId = book(showId, seats.get(0));
		jdbc.update("update shows set start_time = now() + interval '7 hours' where id = ?", showId);
		checkIn(showId, codes.codeFor(bookingId)).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("TOO_EARLY"));

		jdbc.update("update shows set start_time = now() - interval '7 hours' where id = ?", showId);
		checkIn(showId, codes.codeFor(bookingId)).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("TOO_LATE"));
	}

	@Test
	void tenScannersAtOnce_letTheTicketInExactlyOnce() throws Exception {
		String code = codes.codeFor(book(showId, seats.get(0)));
		ExecutorService pool = Executors.newFixedThreadPool(10);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			results.add(pool.submit(() -> {
				go.await();
				return checkIn(showId, code).andReturn().getResponse().getStatus();
			}));
		}
		go.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get(30, TimeUnit.SECONDS));
		}
		pool.shutdown();

		assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
		assertThat(statuses).filteredOn(s -> s == 409).hasSize(9);
	}

	@Test
	void onlyAdminsCheckIn() throws Exception {
		String code = codes.codeFor(book(showId, seats.get(0)));
		mvc.perform(post("/api/admin/check-in").header("Authorization", "Bearer " + user)
						.contentType(MediaType.APPLICATION_JSON).content("{\"showId\":" + showId + ",\"code\":\"" + code + "\"}"))
				.andExpect(status().isForbidden());
	}

	// ---- helpers ----

	ResultActions checkIn(long show, String code) throws Exception {
		return mvc.perform(post("/api/admin/check-in").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"showId\":" + show + ",\"code\":\"" + code + "\"}"));
	}

	int book(long show, Integer... seatIds) throws Exception {
		String body = "{\"showId\":" + show + ",\"seatIds\":" + List.of(seatIds) + "}";
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + user)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		return JsonPath.read(mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + user)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	List<Integer> seatIds(long show) throws Exception {
		return JsonPath.read(mvc.perform(get("/api/shows/" + show + "/seats")).andReturn().getResponse().getContentAsString(), "$[*].id");
	}

	long createShowWithSeats(String title, int hoursAhead) throws Exception {
		int venueId = JsonPath.read(mvc.perform(post("/api/venues").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Door Venue\",\"city\":\"Pune\"}"))
				.andReturn().getResponse().getContentAsString(), "$.id");
		Instant start = Instant.now().plus(hoursAhead, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
		int id = JsonPath.read(mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"" + title + "\",\"startTime\":\"" + start + "\",\"price\":250.00,\"venueId\":" + venueId + "}"))
				.andReturn().getResponse().getContentAsString(), "$.id");
		mvc.perform(post("/api/shows/" + id + "/seats").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"rows\":1,\"seatsPerRow\":6}"));
		return id;
	}

	String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Door Guest\",\"email\":\"" + email + "\",\"password\":\"Door-Pw-12345\"}"));
		return login(email, "Door-Pw-12345");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

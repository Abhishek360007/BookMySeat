package com.abhishekraj.BookMySeat;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Same configuration as BookingApiTest, so it reuses that cached context
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class IdempotencyApiTest {

	@Autowired MockMvc mvc;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;

	private String user;
	private long showId;
	private List<Integer> seats;

	@BeforeEach
	void setUp() throws Exception {
		String admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		showId = createShowWithSeats(admin);
		seats = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats"))
				.andReturn().getResponse().getContentAsString(), "$[*].id");

		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Idem\",\"email\":\"idem-user@example.com\",\"password\":\"Idem-User-Pw-123\"}"));
		user = login("idem-user@example.com", "Idem-User-Pw-123");
	}

	@AfterEach
	void cleanUp() {
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
		redis.delete(redis.keys("idem:*"));
	}

	@Test
	void retryWithSameKey_returnsTheSameBooking_andBooksOnce() throws Exception {
		hold(seats.get(0), seats.get(1));

		String first = confirm("key-retry-1", seats.get(0), seats.get(1))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))
				.andReturn().getResponse().getContentAsString();

		confirm("key-retry-1", seats.get(1), seats.get(0)) // same seats in another order: same request
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.id").value((Integer) JsonPath.read(first, "$.id")))
				.andExpect(jsonPath("$.totalAmount").value(500.00));

		mvc.perform(get("/api/bookings/me").header("Authorization", "Bearer " + user))
				.andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void sameKeyForDifferentSeats_is422() throws Exception {
		hold(seats.get(0));
		confirm("key-reuse-1", seats.get(0)).andExpect(status().isCreated());

		confirm("key-reuse-1", seats.get(2))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.title").value("Idempotency-Key reused"));
	}

	@Test
	void failedRequest_releasesTheKey_soARetryCanSucceed() throws Exception {
		confirm("key-fail-1", seats.get(3)).andExpect(status().isConflict()); // not held yet

		hold(seats.get(3));
		confirm("key-fail-1", seats.get(3))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"));
	}

	@Test
	void malformedKey_is400() throws Exception {
		confirm("bad key with spaces", seats.get(0)).andExpect(status().isBadRequest());
		assertThat(redis.keys("idem:*")).isEmpty();
	}

	private ResultActions confirm(String key, Integer... seatIds) throws Exception {
		return mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + user)
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + "}"));
	}

	private void hold(Integer... seatIds) throws Exception {
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + user)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + "}"))
				.andExpect(status().isCreated());
	}

	private String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}

	private long createShowWithSeats(String admin) throws Exception {
		int venueId = JsonPath.read(mvc.perform(post("/api/venues").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Idem Venue\",\"city\":\"Pune\"}"))
				.andReturn().getResponse().getContentAsString(), "$.id");
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		int id = JsonPath.read(mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Idem Show\",\"startTime\":\"" + start + "\",\"price\":250.00,\"venueId\":" + venueId + "}"))
				.andReturn().getResponse().getContentAsString(), "$.id");
		mvc.perform(post("/api/shows/" + id + "/seats").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"rows\":1,\"seatsPerRow\":5}"));
		return id;
	}
}

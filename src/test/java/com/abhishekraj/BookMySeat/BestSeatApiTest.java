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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "Pick the best seats for me" over HTTP: suggestions on the live seat map, and holding them in one call, also with
// several people asking at once. Same configuration as BookingApiTest (shared Spring context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class BestSeatApiTest {

	@Autowired MockMvc mvc;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;

	String admin;
	long venueId;
	long showId;
	Map<String, Integer> seat; // seat number -> id

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		venueId = ((Number) JsonPath.read(adminCall(post("/api/venues"), "{\"name\":\"Best Seat Venue\",\"city\":\"Pune\"}")
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		// 3 rows of 8 with an aisle after seat 5; row C (the back) is recliners at 500
		showId = createShow(",\"categoryPrices\":{\"RECLINER\":500.00},\"aisleAfter\":[5]");
		adminCall(post("/api/shows/" + showId + "/seats"), "{\"rows\":3,\"seatsPerRow\":8,\"reclinerRows\":1}")
				.andExpect(status().isCreated());
		seat = seatIds(showId);
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
	void suggestion_isTheMiddleOfTheRowAboutTwoThirdsBack_withItsPrice() throws Exception {
		// 3 rows: the sweet spot is row B; 8 seats with an aisle after 5: B4, B5 are central and on one side
		best(2, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.seatNumbers", contains("B4", "B5")))
				.andExpect(jsonPath("$.seatIds", contains(seat.get("B4"), seat.get("B5"))))
				.andExpect(jsonPath("$.category").value("REGULAR"))
				.andExpect(jsonPath("$.totalAmount").value(400.00));
		best(2, "RECLINER").andExpect(jsonPath("$.seatNumbers", contains("C4", "C5")))
				.andExpect(jsonPath("$.totalAmount").value(1000.00));
		best(1, "regular").andExpect(status().isBadRequest()); // categories are in capitals
	}

	@Test
	void heldAndBookedSeats_areNotSuggested() throws Exception {
		String holder = registerAndLogin("best-holder@example.com");
		hold(holder, seat.get("B4")).andExpect(status().isCreated());
		// B can't seat 2 in the middle any more: the middle of the back row (recliners, no category asked) is next best
		best(2, null).andExpect(jsonPath("$.seatNumbers", contains("C4", "C5")));
		hold(holder, seat.get("C4")).andExpect(status().isCreated());
		best(2, null).andExpect(jsonPath("$.seatNumbers", contains("A4", "A5")));

		String buyer = registerAndLogin("best-buyer@example.com");
		hold(buyer, seat.get("A4")).andExpect(status().isCreated());
		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + buyer).contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + showId + ",\"seatIds\":[" + seat.get("A4") + "]}")).andExpect(status().isCreated());
		best(2, null).andExpect(jsonPath("$.seatNumbers", contains("B2", "B3")));
	}

	@Test
	void noBlockThatBig_isA409_andBadInputA400() throws Exception {
		// The aisle splits each row into 5 + 3
		best(5, null).andExpect(status().isOk());
		best(6, null).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("No 6 seats together are free right now"));
		best(4, "PREMIUM").andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("No 4 seats together are free in premium right now"));
		best(0, null).andExpect(status().isBadRequest());
		best(11, null).andExpect(status().isBadRequest());
		mvc.perform(get("/api/shows/999999/seats/best?count=2")).andExpect(status().isNotFound());

		adminCall(post("/api/shows/" + showId + "/cancel"), "").andExpect(status().isOk());
		best(2, null).andExpect(status().isConflict());
	}

	@Test
	void holdBest_holdsThePickedSeats_forTheUsualConfirm() throws Exception {
		String user = registerAndLogin("best-user@example.com");
		holdBest(user, 3, null).andExpect(status().isCreated())
				.andExpect(jsonPath("$.seatNumbers", contains("B3", "B4", "B5")))
				.andExpect(jsonPath("$.expiresAt").isNotEmpty());
		String seats = mvc.perform(get("/api/shows/" + showId + "/seats")).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(seats, "$[?(@.status == 'HELD')].seatNumber")).containsExactly("B3", "B4", "B5");

		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + user).contentType(MediaType.APPLICATION_JSON)
						.content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seat.get("B3"), seat.get("B4"), seat.get("B5")) + "}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(600.00));

		holdBest(user, 2, "RECLINER").andExpect(jsonPath("$.seatNumbers", contains("C4", "C5")));
		mvc.perform(post("/api/bookings/hold/best").contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + showId + ",\"count\":2}")).andExpect(status().isUnauthorized());
		holdBest(user, 11, null).andExpect(status().isBadRequest());
	}

	// 8 people ask for the best 2 seats of a 16-seat row at the same moment: nobody gets a seat someone else got, and a
	// refusal only happens when no 2 free seats are left side by side. (Taking the middle pair first splits the row into
	// two runs of 7, so one seat is always left on its own and the 8th person is told so.)
	@Test
	void peopleAskingAtOnce_neverGetTheSameSeat() throws Exception {
		long rowShow = createShow("");
		adminCall(post("/api/shows/" + rowShow + "/seats"), "{\"rows\":1,\"seatsPerRow\":16}").andExpect(status().isCreated());
		int people = 8;
		List<String> tokens = new ArrayList<>();
		for (int i = 0; i < people; i++) {
			tokens.add(registerAndLogin("best-rush-" + i + "@example.com"));
		}

		ExecutorService pool = Executors.newFixedThreadPool(people);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<MvcResult>> results = new ArrayList<>();
		for (String token : tokens) {
			results.add(pool.submit(() -> {
				start.await();
				return mvc.perform(post("/api/bookings/hold/best").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content("{\"showId\":" + rowShow + ",\"count\":2}")).andReturn();
			}));
		}
		start.countDown();
		Set<String> taken = new HashSet<>();
		int held = 0;
		for (Future<MvcResult> result : results) {
			MvcResult r = result.get(60, TimeUnit.SECONDS);
			String body = r.getResponse().getContentAsString();
			if (r.getResponse().getStatus() == 201) {
				held++;
				List<String> mine = JsonPath.read(body, "$.seatNumbers");
				assertThat(mine).hasSize(2);
				assertThat(taken.addAll(mine)).as("seats %s given twice", mine).isTrue();
			} else {
				assertThat(r.getResponse().getStatus()).isEqualTo(409);
				assertThat(JsonPath.<String>read(body, "$.detail")).isEqualTo("No 2 seats together are free right now");
			}
		}
		pool.shutdown();
		assertThat(held).isGreaterThanOrEqualTo(7);

		// What is left has no two free seats next to each other, so every refusal was right
		List<Integer> free = new ArrayList<>();
		for (int n = 1; n <= 16; n++) {
			if (!taken.contains("A" + n)) {
				free.add(n);
			}
		}
		for (int i = 1; i < free.size(); i++) {
			assertThat(free.get(i) - free.get(i - 1)).as("free seats left together: %s", free).isGreaterThan(1);
		}
		String late = registerAndLogin("best-rush-late@example.com");
		mvc.perform(post("/api/bookings/hold/best").header("Authorization", "Bearer " + late).contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + rowShow + ",\"count\":" + (free.size() + 1) + "}")).andExpect(status().isConflict());
	}

	ResultActions best(int count, String category) throws Exception {
		return mvc.perform(get("/api/shows/" + showId + "/seats/best?count=" + count + (category == null ? "" : "&category=" + category)));
	}

	ResultActions holdBest(String token, int count, String category) throws Exception {
		return mvc.perform(post("/api/bookings/hold/best").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + showId + ",\"count\":" + count + (category == null ? "" : ",\"category\":\"" + category + "\"") + "}"));
	}

	ResultActions hold(String token, Integer seatId) throws Exception {
		return mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"showId\":" + showId + ",\"seatIds\":[" + seatId + "]}"));
	}

	long createShow(String extra) throws Exception {
		Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		return ((Number) JsonPath.read(adminCall(post("/api/shows"), "{\"title\":\"Best Seat Show\",\"startTime\":\"" + start
				+ "\",\"price\":200.00,\"venueId\":" + venueId + extra + "}").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	Map<String, Integer> seatIds(long show) throws Exception {
		String json = mvc.perform(get("/api/shows/" + show + "/seats")).andReturn().getResponse().getContentAsString();
		List<String> numbers = JsonPath.read(json, "$[*].seatNumber");
		List<Integer> ids = JsonPath.read(json, "$[*].id");
		Map<String, Integer> byNumber = new java.util.HashMap<>();
		for (int i = 0; i < ids.size(); i++) {
			byNumber.put(numbers.get(i), ids.get(i));
		}
		return byNumber;
	}

	ResultActions adminCall(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Best Seat Fan\",\"email\":\"" + email + "\",\"password\":\"Best-Seat-Pw-1\"}"));
		return login(email, "Best-Seat-Pw-1");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

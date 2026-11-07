package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.abhishekraj.BookMySeat.repository.WaitlistRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Joining and leaving the waitlist of a sold-out show. Same configuration as BookingApiTest (shared Spring context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WaitlistApiTest {

	@Autowired MockMvc mvc;
	@Autowired WaitlistRepository waitlistRepository;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;

	String admin;
	long showId;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		long venueId = ((Number) JsonPath.read(adminCall(post("/api/venues"), "{\"name\":\"Waitlist Venue\",\"city\":\"Pune\"}")
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		showId = ((Number) JsonPath.read(adminCall(post("/api/shows"),
						"{\"title\":\"Waitlist Show\",\"startTime\":\"" + start + "\",\"price\":200.00,\"venueId\":" + venueId + "}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		adminCall(post("/api/shows/" + showId + "/seats"), "{\"rows\":1,\"seatsPerRow\":2}").andExpect(status().isCreated());
	}

	@AfterEach
	void cleanUp() {
		waitlistRepository.deleteAll();
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
	}

	@Test
	void onlyASoldOutShow_hasAWaitlist_andEachPersonGetsThePlaceTheyJoinedAt() throws Exception {
		String buyer = registerAndLogin("waitlist-buyer@example.com");
		String first = registerAndLogin("waitlist-first@example.com");
		String second = registerAndLogin("waitlist-second@example.com");

		join(first, 2).andExpect(status().isConflict()); // seats are still available
		bookAll(buyer);

		join(first, 2).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("WAITING"))
				.andExpect(jsonPath("$.position").value(1))
				.andExpect(jsonPath("$.seats").value(2))
				.andExpect(jsonPath("$.showTitle").value("Waitlist Show"))
				.andExpect(jsonPath("$.offeredSeatIds").isEmpty());
		join(second, 1).andExpect(status().isCreated()).andExpect(jsonPath("$.position").value(2));
		join(first, 1).andExpect(status().isConflict()); // once per show

		mine(second).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].position").value(2));

		// Leaving moves everyone behind up; leaving twice is a 404; joining again goes to the back
		mvc.perform(delete("/api/waitlist/" + showId).header("Authorization", "Bearer " + first)).andExpect(status().isNoContent());
		mvc.perform(delete("/api/waitlist/" + showId).header("Authorization", "Bearer " + first)).andExpect(status().isNotFound());
		mine(first).andExpect(jsonPath("$").isEmpty());
		mine(second).andExpect(jsonPath("$[0].position").value(1));
		join(first, 2).andExpect(status().isCreated()).andExpect(jsonPath("$.position").value(2));
	}

	@Test
	void invalidRequests_areRefused() throws Exception {
		String user = registerAndLogin("waitlist-invalid@example.com");
		bookAll(user);

		join(user, 0).andExpect(status().isBadRequest());
		join(user, 7).andExpect(status().isBadRequest());
		mvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON).content("{\"showId\":" + showId + ",\"seats\":1}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/waitlist").header("Authorization", "Bearer " + user).contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":999999,\"seats\":1}")).andExpect(status().isNotFound());

		adminCall(post("/api/shows/" + showId + "/cancel"), "").andExpect(status().isOk());
		join(user, 1).andExpect(status().isConflict()); // a cancelled show has no waitlist
	}

	@Test
	void twoJoinsAtOnce_bySamePerson_giveOnePlaceInLine() throws Exception {
		String buyer = registerAndLogin("waitlist-buyer2@example.com");
		String user = registerAndLogin("waitlist-twice@example.com");
		bookAll(buyer);

		int attempts = 6;
		ExecutorService pool = Executors.newFixedThreadPool(attempts);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < attempts; i++) {
			Callable<Integer> call = () -> {
				go.await();
				var response = join(user, 1).andReturn().getResponse();
				if (response.getStatus() == 409) {
					assertThat(response.getContentAsString()).contains("already on the waitlist");
				}
				return response.getStatus();
			};
			results.add(pool.submit(call));
		}
		go.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		pool.shutdown();

		assertThat(statuses).containsOnly(201, 409);
		assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
		mine(user).andExpect(jsonPath("$", hasSize(1)));
	}

	// ---- helpers ----

	ResultActions join(String token, int seats) throws Exception {
		return mvc.perform(post("/api/waitlist").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"showId\":" + showId + ",\"seats\":" + seats + "}"));
	}

	ResultActions mine(String token) throws Exception {
		return mvc.perform(get("/api/waitlist/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
	}

	void bookAll(String token) throws Exception {
		List<Integer> seatIds = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats"))
				.andReturn().getResponse().getContentAsString(), "$[*].id");
		String body = "{\"showId\":" + showId + ",\"seatIds\":" + seatIds + "}";
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
	}

	ResultActions adminCall(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Waitlist Fan\",\"email\":\"" + email + "\",\"password\":\"Waitlist-Pw-123\"}"));
		return login(email, "Waitlist-Pw-123");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

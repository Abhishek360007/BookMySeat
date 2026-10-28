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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Full HTTP stack (security filters, validation, JSON, ProblemDetail) with real logins.
// jsonPath assertions pin the field names: renaming a DTO field is a breaking API change.
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class BookingApiTest {

	private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

	@Autowired MockMvc mvc;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;

	private String adminToken;

	@BeforeEach
	void setUp() throws Exception {
		adminToken = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
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
	void noToken_is401ProblemJson() throws Exception {
		mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
						.content("{\"showId\":1,\"seatIds\":[1]}"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(401));
	}

	@Test
	void userTokenOnAdminEndpoint_is403ProblemJson() throws Exception {
		String user = registerAndLogin("api-user1@example.com");

		mvc.perform(get("/api/admin/shows/1/summary").header("Authorization", "Bearer " + user))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(403));
	}

	@Test
	void emptySeatIds_is400WithFieldError() throws Exception {
		String user = registerAndLogin("api-user1@example.com");

		perform(post("/api/bookings"), user, "{\"showId\":1,\"seatIds\":[]}")
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.seatIds").isNotEmpty());
	}

	@Test
	void fullFlow_holdConfirmListCancel() throws Exception {
		long showId = createShowWithSeats("300.00");
		List<Integer> seatIds = seatIds(showId).subList(0, 2);
		String user = registerAndLogin("api-user1@example.com");
		String body = "{\"showId\":" + showId + ",\"seatIds\":" + seatIds + "}";

		perform(post("/api/bookings/hold"), user, body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.seatNumbers", hasSize(2)))
				.andExpect(jsonPath("$.expiresAt").isNotEmpty());

		String booking = perform(post("/api/bookings"), user, body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.seats", hasSize(2)))
				.andExpect(jsonPath("$.totalAmount").value(600.00))
				.andReturn().getResponse().getContentAsString();
		int bookingId = JsonPath.read(booking, "$.id");

		mvc.perform(get("/api/bookings/me").header("Authorization", "Bearer " + user))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(bookingId));

		perform(post("/api/bookings/" + bookingId + "/cancel"), user, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"))
				.andExpect(jsonPath("$.cancelledAt").isNotEmpty());
	}

	@Test
	void secondUserHoldingSameSeat_is409ProblemJson() throws Exception {
		long showId = createShowWithSeats("300.00");
		String body = "{\"showId\":" + showId + ",\"seatIds\":[" + seatIds(showId).getFirst() + "]}";

		perform(post("/api/bookings/hold"), registerAndLogin("api-user1@example.com"), body)
				.andExpect(status().isCreated());

		perform(post("/api/bookings/hold"), registerAndLogin("api-user2@example.com"), body)
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.detail", containsString("held by someone else")));
	}

	// ---- helpers: everything goes through the real endpoints ----

	private ResultActions perform(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
		request.header("Authorization", "Bearer " + token);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(request);
	}

	private String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"API Test\",\"email\":\"" + email + "\",\"password\":\"Api-User-Pw-123\"}"))
				.andExpect(status().isCreated());
		return login(email, "Api-User-Pw-123");
	}

	private String login(String email, String password) throws Exception {
		String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken", not(emptyString())))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.accessToken");
	}

	// Starts in 1 day, so neither the "already started" check nor the cancel cutoff gets in the way
	private long createShowWithSeats(String price) throws Exception {
		String venue = perform(post("/api/venues"), adminToken,
				"{\"name\":\"API Test Venue\",\"city\":\"Test City\",\"address\":\"1 Test Road\"}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		int venueId = JsonPath.read(venue, "$.id");

		Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		String show = perform(post("/api/shows"), adminToken,
				"{\"title\":\"API Test Show\",\"startTime\":\"" + start + "\",\"price\":" + price + ",\"venueId\":" + venueId + "}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.price").value(Double.parseDouble(price)))
				.andReturn().getResponse().getContentAsString();
		int showId = JsonPath.read(show, "$.id");

		perform(post("/api/shows/" + showId + "/seats"), adminToken, "{\"rows\":1,\"seatsPerRow\":5}")
				.andExpect(status().isCreated());
		return showId;
	}

	private List<Integer> seatIds(long showId) throws Exception {
		String seats = mvc.perform(get("/api/shows/" + showId + "/seats"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(seats, "$[*].id");
	}
}

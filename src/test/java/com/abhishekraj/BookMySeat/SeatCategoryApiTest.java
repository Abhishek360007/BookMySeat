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
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Seat categories: layout by row, a price per category, aisles, and totals that add each seat's price.
// Same configuration as BookingApiTest (shared Spring context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SeatCategoryApiTest {

	@Autowired MockMvc mvc;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;

	String admin;
	long venueId;
	Instant start;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		venueId = ((Number) JsonPath.read(adminCall(post("/api/venues"), "{\"name\":\"Category Venue\",\"city\":\"Pune\"}")
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
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
	void rowsFromTheBack_becomeRecliners_thenPremium_withTheirPrices_andAisles() throws Exception {
		long showId = createShow("{\"PREMIUM\":350.00,\"RECLINER\":500.00}", "[8,4]");
		adminCall(post("/api/shows/" + showId + "/seats"), "{\"rows\":5,\"seatsPerRow\":10,\"premiumRows\":2,\"reclinerRows\":1}")
				.andExpect(status().isCreated());

		mvc.perform(get("/api/shows/" + showId))
				.andExpect(jsonPath("$.price").value(250.00))
				.andExpect(jsonPath("$.prices.REGULAR").value(250.00))
				.andExpect(jsonPath("$.prices.PREMIUM").value(350.00))
				.andExpect(jsonPath("$.prices.RECLINER").value(500.00))
				.andExpect(jsonPath("$.aisleAfter", contains(4, 8)));

		String seats = mvc.perform(get("/api/shows/" + showId + "/seats")).andReturn().getResponse().getContentAsString();
		Map<String, String> categoryOf = Map.of("A1", "REGULAR", "B10", "REGULAR", "C1", "PREMIUM", "D5", "PREMIUM", "E1", "RECLINER");
		categoryOf.forEach((seat, category) -> {
			org.assertj.core.api.Assertions.assertThat(JsonPath.<List<String>>read(seats, "$[?(@.seatNumber=='" + seat + "')].category"))
					.as(seat).containsExactly(category);
		});
		org.assertj.core.api.Assertions.assertThat(JsonPath.<List<Double>>read(seats, "$[?(@.seatNumber=='E10')].price"))
				.containsExactly(500.0);
	}

	@Test
	void aCategoryWithoutItsOwnPrice_costsTheRegularPrice() throws Exception {
		long showId = createShow("{\"RECLINER\":500.00}", null);
		mvc.perform(get("/api/shows/" + showId))
				.andExpect(jsonPath("$.prices.PREMIUM").value(250.00))
				.andExpect(jsonPath("$.aisleAfter").isEmpty());
	}

	@Test
	void invalidLayoutsAndPrices_are400() throws Exception {
		long showId = createShow(null, null);
		adminCall(post("/api/shows/" + showId + "/seats"), "{\"rows\":3,\"seatsPerRow\":10,\"premiumRows\":2,\"reclinerRows\":2}")
				.andExpect(status().isBadRequest());
		adminCall(post("/api/shows"), showJson("{\"PREMIUM\":-1}", null)).andExpect(status().isBadRequest());
		adminCall(post("/api/shows"), showJson(null, "[30]")).andExpect(status().isBadRequest());
	}

	@Test
	void theTotal_addsEachSeatsCategoryPrice_andKeepsItWhenPricesChange() throws Exception {
		long showId = createShow("{\"PREMIUM\":350.00,\"RECLINER\":500.00}", null);
		adminCall(post("/api/shows/" + showId + "/seats"), "{\"rows\":3,\"seatsPerRow\":4,\"premiumRows\":1,\"reclinerRows\":1}");
		String user = registerAndLogin();
		String seats = mvc.perform(get("/api/shows/" + showId + "/seats")).andReturn().getResponse().getContentAsString();

		// A1 regular 250 + B1 premium 350 + C1 recliner 500
		String booked = book(user, showId, seatId(seats, "A1"), seatId(seats, "B1"), seatId(seats, "C1"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.totalAmount").value(1100.00))
				.andReturn().getResponse().getContentAsString();
		int bookingId = JsonPath.read(booked, "$.id");

		adminCall(put("/api/shows/" + showId), showJson("{\"PREMIUM\":350.00,\"RECLINER\":650.00}", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.prices.RECLINER").value(650.00));

		book(user, showId, seatId(seats, "C2")).andExpect(jsonPath("$.totalAmount").value(650.00));
		mvc.perform(get("/api/bookings/me").header("Authorization", "Bearer " + user))
				.andExpect(jsonPath("$[?(@.id==" + bookingId + ")].totalAmount", contains(1100.00)));
	}

	@Test
	void changingTheLayoutLater_keepsWhatEarlierBookingsCost() throws Exception {
		long showId = createShow("{\"RECLINER\":500.00}", null);
		adminCall(post("/api/shows/" + showId + "/seats"), "{\"rows\":3,\"seatsPerRow\":4}").andExpect(status().isCreated());
		String user = registerAndLogin();
		String seats = mvc.perform(get("/api/shows/" + showId + "/seats")).andReturn().getResponse().getContentAsString();
		book(user, showId, seatId(seats, "C1")).andExpect(jsonPath("$.totalAmount").value(250.00)); // regular then

		adminCall(put("/api/shows/" + showId + "/seats/layout"), "{\"premiumRows\":0,\"reclinerRows\":1}")
				.andExpect(status().isOk());
		String after = mvc.perform(get("/api/shows/" + showId + "/seats")).andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(JsonPath.<List<String>>read(after, "$[?(@.seatNumber=='C2')].category"))
				.containsExactly("RECLINER");
		book(user, showId, seatId(after, "C2")).andExpect(jsonPath("$.totalAmount").value(500.00));
		mvc.perform(get("/api/bookings/me").header("Authorization", "Bearer " + user))
				.andExpect(jsonPath("$[?(@.seats[0]=='C1')].totalAmount", contains(250.00))); // unchanged

		adminCall(put("/api/shows/" + showId + "/seats/layout"), "{\"premiumRows\":2,\"reclinerRows\":2}")
				.andExpect(status().isBadRequest());
		mvc.perform(put("/api/shows/" + showId + "/seats/layout").header("Authorization", "Bearer " + user)
						.contentType(MediaType.APPLICATION_JSON).content("{\"premiumRows\":1,\"reclinerRows\":0}"))
				.andExpect(status().isForbidden());
	}

	// ---- helpers ----

	static int seatId(String seatsJson, String seatNumber) {
		return JsonPath.<List<Integer>>read(seatsJson, "$[?(@.seatNumber=='" + seatNumber + "')].id").getFirst();
	}

	ResultActions book(String token, long showId, Integer... seatIds) throws Exception {
		String body = "{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + "}";
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		return mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	long createShow(String categoryPrices, String aisles) throws Exception {
		return ((Number) JsonPath.read(adminCall(post("/api/shows"), showJson(categoryPrices, aisles))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	String showJson(String categoryPrices, String aisles) {
		return "{\"title\":\"Category Show\",\"startTime\":\"" + start + "\",\"price\":250.00,\"venueId\":" + venueId
				+ (categoryPrices == null ? "" : ",\"categoryPrices\":" + categoryPrices)
				+ (aisles == null ? "" : ",\"aisleAfter\":" + aisles) + "}";
	}

	ResultActions adminCall(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	String registerAndLogin() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Category Fan\",\"email\":\"category-fan@example.com\",\"password\":\"Category-Pw-123\"}"));
		return login("category-fan@example.com", "Category-Pw-123");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

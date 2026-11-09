package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.PromoCodeRepository;
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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Promo codes: admins create and switch them off, customers price their seats with one. Same configuration as BookingApiTest (shared Spring context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PromoCodeApiTest {

	@Autowired MockMvc mvc;
	@Autowired PromoCodeRepository promoCodeRepository;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;

	String admin;
	long venueId;
	long showId;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		venueId = ((Number) JsonPath.read(adminCall(post("/api/venues"), "{\"name\":\"Promo Venue\",\"city\":\"Pune\"}")
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		showId = createShow("Promo Show");
	}

	@AfterEach
	void cleanUp() {
		promoCodeRepository.deleteAll();
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("rate:*"));
	}

	@Test
	void admin_createsCodes_inCapitals_andListsThemNewestFirst() throws Exception {
		createCode("{\"code\":\" diwali25 \",\"description\":\"Diwali offer\",\"kind\":\"PERCENT\",\"amount\":25,\"maxDiscount\":150,\"maxUses\":100}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value("DIWALI25"))
				.andExpect(jsonPath("$.kind").value("PERCENT"))
				.andExpect(jsonPath("$.maxDiscount").value(150))
				.andExpect(jsonPath("$.maxUses").value(100))
				.andExpect(jsonPath("$.maxUsesPerUser").value(1))
				.andExpect(jsonPath("$.uses").value(0))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.validFrom").isNotEmpty())
				.andExpect(jsonPath("$.showId").isEmpty());
		createCode("{\"code\":\"PROMOSHOW\",\"kind\":\"FLAT\",\"amount\":50,\"showId\":" + showId + ",\"maxUsesPerUser\":3}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.showTitle").value("Promo Show"))
				.andExpect(jsonPath("$.maxUsesPerUser").value(3));

		adminCall(get("/api/admin/promo-codes"), "").andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(2)))
				.andExpect(jsonPath("$.content[0].code").value("PROMOSHOW"))
				.andExpect(jsonPath("$.content[1].code").value("DIWALI25"));

		// The same code twice, in any case, is a conflict
		createCode("{\"code\":\"Diwali25\",\"kind\":\"FLAT\",\"amount\":10}").andExpect(status().isConflict());
	}

	@Test
	void invalidCodes_areRefused() throws Exception {
		createCode("{\"code\":\"AB\",\"kind\":\"FLAT\",\"amount\":10}").andExpect(status().isBadRequest()); // too short
		createCode("{\"code\":\"NO SPACES\",\"kind\":\"FLAT\",\"amount\":10}").andExpect(status().isBadRequest());
		createCode("{\"code\":\"ZERO\",\"kind\":\"FLAT\",\"amount\":0}").andExpect(status().isBadRequest());
		createCode("{\"code\":\"TOOMUCH\",\"kind\":\"PERCENT\",\"amount\":101}").andExpect(status().isBadRequest());
		createCode("{\"code\":\"CAPFLAT\",\"kind\":\"FLAT\",\"amount\":10,\"maxDiscount\":5}").andExpect(status().isBadRequest());
		createCode("{\"code\":\"NOKIND\",\"amount\":10}").andExpect(status().isBadRequest());
		createCode("{\"code\":\"PAST\",\"kind\":\"FLAT\",\"amount\":10,\"validUntil\":\"2020-01-01T00:00:00Z\"}").andExpect(status().isBadRequest());
		Instant later = Instant.now().plus(10, ChronoUnit.DAYS);
		createCode("{\"code\":\"BACKWARDS\",\"kind\":\"FLAT\",\"amount\":10,\"validFrom\":\"" + later
				+ "\",\"validUntil\":\"" + later.minus(1, ChronoUnit.DAYS) + "\"}").andExpect(status().isBadRequest());
		createCode("{\"code\":\"NOSHOW\",\"kind\":\"FLAT\",\"amount\":10,\"showId\":999999}").andExpect(status().isNotFound());
		adminCall(get("/api/admin/promo-codes"), "").andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void onlyAdmins_manageCodes() throws Exception {
		String user = registerAndLogin("promo-customer@example.com");
		mvc.perform(get("/api/admin/promo-codes").header("Authorization", "Bearer " + user)).andExpect(status().isForbidden());
		mvc.perform(post("/api/admin/promo-codes").header("Authorization", "Bearer " + user)
						.contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"MINE\",\"kind\":\"FLAT\",\"amount\":10}"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/promo-codes/preview").contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"X\",\"showId\":1,\"seatIds\":[1]}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void preview_pricesTheSeats_withTheDiscount() throws Exception {
		List<Integer> seats = seats(showId, 3); // 3 × 200
		String user = registerAndLogin("promo-preview@example.com");
		createCode("{\"code\":\"TENOFF\",\"description\":\"10% off\",\"kind\":\"PERCENT\",\"amount\":10}").andExpect(status().isCreated());
		createCode("{\"code\":\"HALFCAP\",\"kind\":\"PERCENT\",\"amount\":50,\"maxDiscount\":120}").andExpect(status().isCreated());
		createCode("{\"code\":\"FLAT1000\",\"kind\":\"FLAT\",\"amount\":1000}").andExpect(status().isCreated());

		preview(user, "tenoff", seats).andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("TENOFF"))
				.andExpect(jsonPath("$.description").value("10% off"))
				.andExpect(jsonPath("$.subtotal").value(600.00))
				.andExpect(jsonPath("$.discount").value(60.00))
				.andExpect(jsonPath("$.total").value(540.00));
		preview(user, "HALFCAP", seats).andExpect(jsonPath("$.discount").value(120.00)).andExpect(jsonPath("$.total").value(480.00));
		preview(user, "FLAT1000", seats).andExpect(jsonPath("$.discount").value(599.00)).andExpect(jsonPath("$.total").value(1.00));
		preview(user, "TENOFF", seats.subList(0, 1)).andExpect(jsonPath("$.total").value(180.00));
	}

	@Test
	void preview_explainsWhyACodeCantBeUsed() throws Exception {
		List<Integer> seats = seats(showId, 2);
		long otherShow = createShow("Other Show");
		String user = registerAndLogin("promo-refused@example.com");
		Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
		createCode("{\"code\":\"OTHERSHOW\",\"kind\":\"FLAT\",\"amount\":50,\"showId\":" + otherShow + "}").andExpect(status().isCreated());
		createCode("{\"code\":\"BIGORDER\",\"kind\":\"FLAT\",\"amount\":50,\"minTotal\":1000}").andExpect(status().isCreated());
		createCode("{\"code\":\"LATER\",\"kind\":\"FLAT\",\"amount\":50,\"validFrom\":\"" + tomorrow + "\"}").andExpect(status().isCreated());
		long off = id(createCode("{\"code\":\"SWITCHED\",\"kind\":\"FLAT\",\"amount\":50}"));

		refused(preview(user, "NOPE", seats), "This code doesn't exist");
		refused(preview(user, "OTHERSHOW", seats), "This code is for another show");
		refused(preview(user, "BIGORDER", seats), "This code needs a total of at least ₹1000.00");
		refused(preview(user, "LATER", seats), "This code isn't valid yet");

		preview(user, "SWITCHED", seats).andExpect(status().isOk());
		adminCall(post("/api/admin/promo-codes/" + off + "/disable"), "").andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));
		refused(preview(user, "SWITCHED", seats), "This code is no longer active");
		adminCall(post("/api/admin/promo-codes/" + off + "/enable"), "").andExpect(jsonPath("$.active").value(true));
		preview(user, "SWITCHED", seats).andExpect(status().isOk());
		adminCall(post("/api/admin/promo-codes/999999/disable"), "").andExpect(status().isNotFound());

		// Seats of another show (asked for with this show), or a blank code, aren't priced at all
		List<Integer> otherSeats = seats(otherShow, 1);
		preview(user, "SWITCHED", otherSeats).andExpect(status().isNotFound());
		preview(user, " ", seats).andExpect(status().isBadRequest());
	}

	@Test
	void preview_isRateLimited_soCodesCantBeGuessed() throws Exception {
		List<Integer> seats = seats(showId, 1);
		String user = registerAndLogin("promo-guesser@example.com");
		for (int i = 0; i < 20; i++) {
			preview(user, "GUESS" + i, seats).andExpect(status().isUnprocessableContent());
		}
		preview(user, "GUESS20", seats).andExpect(status().isTooManyRequests());
	}

	void refused(ResultActions result, String reason) throws Exception {
		result.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.title").value("Promo code not applicable"))
				.andExpect(jsonPath("$.detail").value(reason));
	}

	ResultActions preview(String token, String code, List<Integer> seatIds) throws Exception {
		return mvc.perform(post("/api/promo-codes/preview").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"" + code + "\",\"showId\":" + showId + ",\"seatIds\":" + seatIds + "}"));
	}

	ResultActions createCode(String body) throws Exception {
		return adminCall(post("/api/admin/promo-codes"), body);
	}

	long id(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	long createShow(String title) throws Exception {
		Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		return id(adminCall(post("/api/shows"),
				"{\"title\":\"" + title + "\",\"startTime\":\"" + start + "\",\"price\":200.00,\"venueId\":" + venueId + "}"));
	}

	List<Integer> seats(long show, int count) throws Exception {
		adminCall(post("/api/shows/" + show + "/seats"), "{\"rows\":1,\"seatsPerRow\":" + count + "}").andExpect(status().isCreated());
		return JsonPath.read(mvc.perform(get("/api/shows/" + show + "/seats")).andReturn().getResponse().getContentAsString(), "$[*].id");
	}

	ResultActions adminCall(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Promo Fan\",\"email\":\"" + email + "\",\"password\":\"Promo-Pw-12345\"}"));
		return login(email, "Promo-Pw-12345");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

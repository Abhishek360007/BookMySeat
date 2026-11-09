package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.notification.OutboxEmailRepository;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.PromoCodeRepository;
import com.abhishekraj.BookMySeat.repository.PromoRedemptionRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Promo codes on bookings: the discount, the limits (also under parallel bookings), and uses given back on cancel.
// Same configuration as BookingApiTest (shared Spring context); online payment is off, so bookings confirm directly.
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PromoCodeBookingApiTest {

	@Autowired MockMvc mvc;
	@Autowired PromoCodeRepository promoCodeRepository;
	@Autowired PromoRedemptionRepository redemptionRepository;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired OutboxEmailRepository outboxRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired MeterRegistry meterRegistry;
	@Autowired JdbcTemplate jdbc;

	String admin;
	long showId;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		long venueId = ((Number) JsonPath.read(adminCall(post("/api/venues"), "{\"name\":\"Promo Booking Venue\",\"city\":\"Pune\"}")
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		showId = ((Number) JsonPath.read(adminCall(post("/api/shows"),
						"{\"title\":\"Promo Booking Show\",\"startTime\":\"" + start + "\",\"price\":200.00,\"venueId\":" + venueId + "}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	@AfterEach
	void cleanUp() {
		promoCodeRepository.deleteAll();
		outboxRepository.deleteAll();
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
		redis.delete(redis.keys("idem:*"));
		redis.delete(redis.keys("rate:*"));
	}

	@Test
	void aCode_takesItsDiscountOff_andTheBookingKeepsIt() throws Exception {
		List<Integer> seats = seats(3);
		String user = registerAndLogin("promo-booker@example.com");
		long code = createCode("{\"code\":\"TENOFF\",\"kind\":\"PERCENT\",\"amount\":10}");
		double redeemedBefore = uses("redeemed");

		book(user, "tenoff", seats.get(0), seats.get(1)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.totalAmount").value(360.00))
				.andExpect(jsonPath("$.discountAmount").value(40.00))
				.andExpect(jsonPath("$.promoCode").value("TENOFF"));
		assertThat(promo(code, "uses")).isEqualTo(1);
		assertThat(uses("redeemed")).isEqualTo(redeemedBefore + 1);

		// Changing the code afterwards doesn't touch the booking
		adminCall(post("/api/admin/promo-codes/" + code + "/disable"), "").andExpect(status().isOk());
		mvc.perform(get("/api/bookings/me").header("Authorization", "Bearer " + user))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].totalAmount").value(360.00))
				.andExpect(jsonPath("$[0].discountAmount").value(40.00))
				.andExpect(jsonPath("$[0].promoCode").value("TENOFF"));

		// Without a code: no discount
		book(user, null, seats.get(2)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.totalAmount").value(200.00))
				.andExpect(jsonPath("$.discountAmount").value(0))
				.andExpect(jsonPath("$.promoCode").isEmpty());
	}

	@Test
	void aRefusedCode_booksNothing_andTheSeatsStayHeld() throws Exception {
		List<Integer> seats = seats(2);
		String user = registerAndLogin("promo-once@example.com");
		createCode("{\"code\":\"ONCE\",\"kind\":\"FLAT\",\"amount\":50}"); // once per person by default
		double refusedBefore = uses("refused");

		book(user, "ONCE", seats.get(0)).andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(150.00));
		book(user, "ONCE", seats.get(1)).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.detail").value("You've already used this code"));
		book(user, "NOSUCHCODE", seats.get(1)).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.detail").value("This code doesn't exist"));
		assertThat(uses("refused")).isEqualTo(refusedBefore + 2);
		assertThat(bookingRepository.count()).isEqualTo(1);

		// Still held by them: booking without the code goes through
		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + user).contentType(MediaType.APPLICATION_JSON)
						.content("{\"showId\":" + showId + ",\"seatIds\":[" + seats.get(1) + "]}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(200.00));
	}

	@Test
	void aCodeForSeveralUses_perPerson_countsEachBooking() throws Exception {
		List<Integer> seats = seats(3);
		String user = registerAndLogin("promo-thrice@example.com");
		createCode("{\"code\":\"TWICE\",\"kind\":\"FLAT\",\"amount\":20,\"maxUsesPerUser\":2}");

		book(user, "TWICE", seats.get(0)).andExpect(status().isCreated());
		book(user, "TWICE", seats.get(1)).andExpect(status().isCreated());
		book(user, "TWICE", seats.get(2)).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.detail").value("You've already used this code 2 times"));
	}

	@Test
	void cancelling_givesTheUseBack() throws Exception {
		List<Integer> seats = seats(2);
		String first = registerAndLogin("promo-first@example.com");
		String second = registerAndLogin("promo-second@example.com");
		long code = createCode("{\"code\":\"JUSTONE\",\"kind\":\"FLAT\",\"amount\":50,\"maxUses\":1}");
		double releasedBefore = uses("released");

		int booking = id(book(first, "JUSTONE", seats.get(0)));
		book(second, "JUSTONE", seats.get(1)).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.detail").value("This code has been fully used"));
		preview(second, "JUSTONE", seats.get(1)).andExpect(jsonPath("$.detail").value("This code has been fully used"));

		mvc.perform(post("/api/bookings/" + booking + "/cancel").header("Authorization", "Bearer " + first)).andExpect(status().isOk());
		assertThat(promo(code, "uses")).isEqualTo(0);
		assertThat(uses("released")).isEqualTo(releasedBefore + 1);
		assertThat(jdbc.queryForObject("select status from promo_redemptions where booking_id = ?", String.class, booking))
				.isEqualTo("RELEASED");

		book(second, "JUSTONE", seats.get(1)).andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(150.00));
		// The freed use went to the second person, so the code is full again for everyone
		preview(first, "JUSTONE", seats.get(0)).andExpect(jsonPath("$.detail").value("This code has been fully used"));
		assertThat(promo(code, "uses")).isEqualTo(1);
	}

	@Test
	void cancellingTheShow_givesEveryUseBack() throws Exception {
		List<Integer> seats = seats(2);
		long code = createCode("{\"code\":\"SHOWDAY\",\"kind\":\"FLAT\",\"amount\":30}");
		book(registerAndLogin("promo-show-a@example.com"), "SHOWDAY", seats.get(0)).andExpect(status().isCreated());
		book(registerAndLogin("promo-show-b@example.com"), "SHOWDAY", seats.get(1)).andExpect(status().isCreated());
		assertThat(promo(code, "uses")).isEqualTo(2);

		adminCall(post("/api/shows/" + showId + "/cancel"), "").andExpect(status().isOk());
		assertThat(promo(code, "uses")).isEqualTo(0);
	}

	@Test
	void theSameIdempotencyKey_withAnotherCode_isRefused() throws Exception {
		List<Integer> seats = seats(1);
		String user = registerAndLogin("promo-idem@example.com");
		createCode("{\"code\":\"FIRSTCODE\",\"kind\":\"FLAT\",\"amount\":10}");
		createCode("{\"code\":\"OTHERCODE\",\"kind\":\"FLAT\",\"amount\":90}");
		hold(user, seats.get(0));

		mvc.perform(confirm(user, "FIRSTCODE", seats.get(0)).header("Idempotency-Key", "promo-key-1"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(190.00));
		mvc.perform(confirm(user, "firstcode", seats.get(0)).header("Idempotency-Key", "promo-key-1"))
				.andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"));
		mvc.perform(confirm(user, "OTHERCODE", seats.get(0)).header("Idempotency-Key", "promo-key-1"))
				.andExpect(status().isUnprocessableContent());
	}

	// 12 people hold a seat each and book at the same moment with a code that has 5 uses: exactly 5 get it
	@Test
	void parallelBookings_neverUseACodeMoreOftenThanItsLimit() throws Exception {
		int people = 12;
		List<Integer> seats = seats(people);
		long code = createCode("{\"code\":\"RUSH5\",\"kind\":\"FLAT\",\"amount\":25,\"maxUses\":5}");
		List<String> tokens = new ArrayList<>();
		for (int i = 0; i < people; i++) {
			String token = registerAndLogin("promo-rush-" + i + "@example.com");
			hold(token, seats.get(i));
			tokens.add(token);
		}

		List<Integer> statuses = race(people, i -> confirm(tokens.get(i), "RUSH5", seats.get(i)));

		assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
		assertThat(statuses).filteredOn(s -> s == 422).hasSize(people - 5);
		assertThat(promo(code, "uses")).isEqualTo(5);
		assertThat(redemptionRepository.count()).isEqualTo(5);
		assertThat(jdbc.queryForObject("select count(*) from bookings where promo_code = 'RUSH5'", Integer.class)).isEqualTo(5);
	}

	// One person books four seats separately at the same moment with a once-per-person code: only one gets it
	@Test
	void parallelBookingsOfOnePerson_useTheirCodeOnce() throws Exception {
		List<Integer> seats = seats(4);
		String token = registerAndLogin("promo-greedy@example.com");
		createCode("{\"code\":\"MINEONLY\",\"kind\":\"FLAT\",\"amount\":40}");
		for (Integer seat : seats) {
			hold(token, seat);
		}

		List<Integer> statuses = race(4, i -> confirm(token, "MINEONLY", seats.get(i)));

		assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
		assertThat(statuses).filteredOn(s -> s == 422).hasSize(3);
		assertThat(redemptionRepository.count()).isEqualTo(1);
	}

	interface Request {
		MockHttpServletRequestBuilder build(int i) throws Exception;
	}

	List<Integer> race(int count, Request request) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				MockHttpServletRequestBuilder built = request.build(i);
				Callable<Integer> call = () -> {
					start.await();
					return mvc.perform(built).andReturn().getResponse().getStatus();
				};
				results.add(pool.submit(call));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
			return statuses;
		} finally {
			pool.shutdownNow();
		}
	}

	double uses(String result) {
		return meterRegistry.counter("promo_codes.uses", "result", result).count();
	}

	int promo(long id, String column) {
		return jdbc.queryForObject("select " + column + " from promo_codes where id = ?", Integer.class, id);
	}

	long createCode(String body) throws Exception {
		return ((Number) JsonPath.read(adminCall(post("/api/admin/promo-codes"), body).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	int id(ResultActions result) throws Exception {
		return JsonPath.read(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	List<Integer> seats(int count) throws Exception {
		adminCall(post("/api/shows/" + showId + "/seats"), "{\"rows\":1,\"seatsPerRow\":" + count + "}").andExpect(status().isCreated());
		return JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats")).andReturn().getResponse().getContentAsString(), "$[*].id");
	}

	void hold(String token, Integer seatId) throws Exception {
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + showId + ",\"seatIds\":[" + seatId + "]}")).andExpect(status().isCreated());
	}

	MockHttpServletRequestBuilder confirm(String token, String code, Integer... seatIds) {
		String promo = code == null ? "" : ",\"promoCode\":\"" + code + "\"";
		return post("/api/bookings").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + promo + "}");
	}

	ResultActions book(String token, String code, Integer... seatIds) throws Exception {
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + "}"));
		return mvc.perform(confirm(token, code, seatIds));
	}

	ResultActions preview(String token, String code, Integer seatId) throws Exception {
		return mvc.perform(post("/api/promo-codes/preview").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"" + code + "\",\"showId\":" + showId + ",\"seatIds\":[" + seatId + "]}"));
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

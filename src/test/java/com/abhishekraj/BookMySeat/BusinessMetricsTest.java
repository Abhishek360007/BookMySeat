package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.notification.OutboxDispatcher;
import com.abhishekraj.BookMySeat.notification.OutboxEmailRepository;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.abhishekraj.BookMySeat.support.AfterCommit;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The business metrics a dashboard and alerts are built on. Counters are shared by every test in this
// context, so each assertion looks at the change, not the absolute value.
@SpringBootTest(properties = {
		"app.admin.email=metrics-test-admin@example.com",
		"app.admin.password=Metrics-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000",
		"email.outbox.initial-delay=PT1H" // the test refreshes the outbox gauges itself
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class BusinessMetricsTest {

	@Autowired MockMvc mvc;
	@Autowired MeterRegistry meters;
	@Autowired OutboxDispatcher outboxDispatcher;
	@Autowired OutboxEmailRepository outboxRepository;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired TransactionTemplate transactions;
	@Autowired JdbcTemplate jdbc;

	private String admin;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("metrics-test-admin@example.com", "Metrics-Test-Admin-Pw-1");
	}

	@AfterEach
	void cleanUp() {
		outboxRepository.deleteAll();
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
	}

	@Test
	void booking_countsSeatsByCategoryAndRevenue_thenCustomerCancel() throws Exception {
		long showId = createShowWithPremiumRow();
		String seats = mvc.perform(get("/api/shows/" + showId + "/seats")).andReturn().getResponse().getContentAsString();
		List<Integer> regular = JsonPath.read(seats, "$[?(@.category == 'REGULAR')].id");
		List<Integer> premium = JsonPath.read(seats, "$[?(@.category == 'PREMIUM')].id");
		String user = registerAndLogin("metrics-user1@example.com");
		double confirmed = count("bookings.confirmed");
		double regularSeats = count("bookings.seats", "category", "regular");
		double premiumSeats = count("bookings.seats", "category", "premium");
		double revenue = count("bookings.revenue");
		double cancelled = count("bookings.cancelled", "by", "customer");

		int bookingId = book(user, showId, List.of(regular.get(0), premium.get(0), premium.get(1)));

		assertThat(count("bookings.confirmed")).isEqualTo(confirmed + 1);
		assertThat(count("bookings.seats", "category", "regular")).isEqualTo(regularSeats + 1);
		assertThat(count("bookings.seats", "category", "premium")).isEqualTo(premiumSeats + 2);
		assertThat(count("bookings.revenue")).isCloseTo(revenue + 250 + 2 * 400, within(0.001));

		call(post("/api/bookings/" + bookingId + "/cancel"), user, null).andExpect(status().isOk());
		assertThat(count("bookings.cancelled", "by", "customer")).isEqualTo(cancelled + 1);
	}

	@Test
	void cancellingAShow_countsEachBookingAsCancelledByTheShow() throws Exception {
		long showId = createShowWithPremiumRow();
		List<Integer> seatIds = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats"))
				.andReturn().getResponse().getContentAsString(), "$[*].id");
		book(registerAndLogin("metrics-user1@example.com"), showId, List.of(seatIds.get(0)));
		book(registerAndLogin("metrics-user2@example.com"), showId, List.of(seatIds.get(1)));
		double byShow = count("bookings.cancelled", "by", "show");
		double byCustomer = count("bookings.cancelled", "by", "customer");

		call(post("/api/shows/" + showId + "/cancel"), admin, null).andExpect(status().isOk());

		assertThat(count("bookings.cancelled", "by", "show")).isEqualTo(byShow + 2);
		assertThat(count("bookings.cancelled", "by", "customer")).isEqualTo(byCustomer);
	}

	@Test
	void businessCounters_existFromStartup_soTheFirstEventAfterARestartIsCounted() {
		for (String[] meter : new String[][] {
				{"bookings.confirmed"}, {"bookings.revenue"},
				{"bookings.seats", "category", "regular"}, {"bookings.seats", "category", "premium"},
				{"bookings.seats", "category", "recliner"},
				{"bookings.cancelled", "by", "customer"}, {"bookings.cancelled", "by", "show"},
				{"payments", "outcome", "confirmed"}, {"payments", "outcome", "refund"},
				{"refunds", "outcome", "refunded"}, {"refunds", "outcome", "manual"},
				{"checkins", "result", "ok"}, {"checkins", "result", "too_early"}}) {
			String[] tags = Arrays.copyOfRange(meter, 1, meter.length);
			assertThat(meters.find(meter[0]).tags(tags).counter()).as(String.join(" ", meter)).isNotNull();
		}
	}

	@Test
	void afterCommit_skipsRolledBackWork() {
		List<String> ran = new ArrayList<>();

		transactions.executeWithoutResult(tx -> {
			AfterCommit.run(() -> ran.add("rolled back"));
			tx.setRollbackOnly();
		});
		transactions.executeWithoutResult(tx -> AfterCommit.run(() -> ran.add("committed")));
		AfterCommit.run(() -> ran.add("no transaction"));

		assertThat(ran).containsExactly("committed", "no transaction");
	}

	@Test
	void outboxGauges_showPendingAge_andFailedEmails() {
		outboxRepository.deleteAll();
		insertEmail("PENDING", "10 minutes");
		insertEmail("PENDING", "1 minute");
		insertEmail("FAILED", "1 hour");
		insertEmail("SENT", "2 hours");

		outboxDispatcher.refreshBacklog();

		assertThat(gauge("emails.outbox.pending")).isEqualTo(2);
		assertThat(gauge("emails.outbox.oldest_pending_seconds")).isBetween(595.0, 700.0);
		assertThat(gauge("emails.outbox.failed")).isEqualTo(1);

		outboxRepository.deleteAll();
		outboxDispatcher.refreshBacklog();
		assertThat(gauge("emails.outbox.pending")).isZero();
		assertThat(gauge("emails.outbox.oldest_pending_seconds")).isZero();
		assertThat(gauge("emails.outbox.failed")).isZero();
	}

	// ---- helpers ----

	private double count(String name, String... tags) {
		var counter = meters.find(name).tags(tags).counter();
		return counter == null ? 0 : counter.count();
	}

	private void insertEmail(String status, String age) {
		jdbc.update("""
				INSERT INTO email_outbox (kind, recipient, subject, body_text, body_html, status, attempts, next_attempt_at, created_at)
				VALUES ('test', 'metrics@example.com', 'Test', 'text', '<p>html</p>', ?, 0, now(), now() - CAST(? AS interval))""",
				status, age);
	}

	private double gauge(String name) {
		return meters.get(name).gauge().value();
	}

	// 2 rows of 3: the back row is premium (400.00), the front row regular (250.00)
	private long createShowWithPremiumRow() throws Exception {
		int venueId = JsonPath.read(call(post("/api/venues"), admin, "{\"name\":\"Metrics Venue\",\"city\":\"Pune\"}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		int showId = JsonPath.read(call(post("/api/shows"), admin, "{\"title\":\"Metrics Show\",\"startTime\":\"" + start
				+ "\",\"price\":250.00,\"venueId\":" + venueId + ",\"categoryPrices\":{\"PREMIUM\":400.00}}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		call(post("/api/shows/" + showId + "/seats"), admin, "{\"rows\":2,\"seatsPerRow\":3,\"premiumRows\":1}")
				.andExpect(status().isCreated());
		return showId;
	}

	private int book(String user, long showId, List<Integer> seatIds) throws Exception {
		String body = "{\"showId\":" + showId + ",\"seatIds\":" + seatIds + "}";
		call(post("/api/bookings/hold"), user, body).andExpect(status().isCreated());
		return JsonPath.read(call(post("/api/bookings"), user, body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private ResultActions call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
		request.header("Authorization", "Bearer " + token);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(request);
	}

	private String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Metrics Test\",\"email\":\"" + email + "\",\"password\":\"Metrics-User-Pw-123\"}"))
				.andExpect(status().isCreated());
		return login(email, "Metrics-User-Pw-123");
	}

	private String login(String email, String password) throws Exception {
		String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.accessToken");
	}
}

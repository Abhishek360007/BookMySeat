package com.abhishekraj.BookMySeat.payment;

import com.abhishekraj.BookMySeat.TestcontainersConfig;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.notification.OutboxEmail;
import com.abhishekraj.BookMySeat.notification.OutboxEmailRepository;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.PaymentRepository;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.abhishekraj.BookMySeat.payment.PaymentApiTest.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Admin cancelling or editing a show that customers have paid for
@SpringBootTest(properties = {
		"app.admin.email=cancel-test-admin@example.com",
		"app.admin.password=Cancel-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000",
		"payments.enabled=true",
		"payments.razorpay.key-id=rzp_test_fake",
		"payments.razorpay.key-secret=" + PaymentApiTest.KEY_SECRET,
		"payments.razorpay.webhook-secret=" + PaymentApiTest.WEBHOOK_SECRET,
		"email.outbox.initial-delay=PT1H"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, ShowCancellationApiTest.FakeRazorpay.class})
class ShowCancellationApiTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeRazorpay {
		@Bean
		@Primary
		FakePaymentGateway fakePaymentGateway() {
			return new FakePaymentGateway();
		}
	}

	@Autowired MockMvc mvc;
	@Autowired FakePaymentGateway gateway;
	@Autowired PaymentRepository paymentRepository;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired OutboxEmailRepository outboxRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired JdbcTemplate jdbc;

	String admin;
	long venueId;
	long showId;
	Instant start;
	List<Integer> seats;

	@BeforeEach
	void setUp() throws Exception {
		gateway.reset();
		admin = login("cancel-test-admin@example.com", "Cancel-Test-Admin-Pw-1");
		venueId = venue("Cancel Venue");
		start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		showId = JsonPath.<Integer>read(adminCall(post("/api/shows"), showJson("Cancel Show", start, "250.00", venueId))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		adminCall(post("/api/shows/" + showId + "/seats"), "{\"rows\":2,\"seatsPerRow\":10}").andExpect(status().isCreated());
		seats = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats"))
				.andReturn().getResponse().getContentAsString(), "$[*].id");
	}

	@AfterEach
	void cleanUp() {
		outboxRepository.deleteAll();
		paymentRepository.deleteAll();
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
	}

	@Test
	void cancellingAShow_cancelsAndRefundsEveryBooking_emailsEveryone_andHidesTheShow() throws Exception {
		String asha = registerAndLogin("asha@example.com");
		String ravi = registerAndLogin("ravi@example.com");
		pay(asha, "pay_a", seats.get(0), seats.get(1));
		pay(ravi, "pay_r", seats.get(2));

		adminCall(post("/api/shows/" + showId + "/cancel"), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cancelledBookings").value(2));

		assertThat(bookingRepository.findAll()).allSatisfy(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED));
		assertThat(paymentRepository.findAll()).allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED));
		assertThat(gateway.refunds).extracting(FakePaymentGateway.Refund::paymentId).containsExactlyInAnyOrder("pay_a", "pay_r");
		assertThat(emails("SHOW_CANCELLED")).extracting(OutboxEmail::getRecipient).containsExactlyInAnyOrder("asha@example.com", "ravi@example.com");
		assertThat(emails("SHOW_CANCELLED")).allSatisfy(e -> assertThat(e.getBodyText())
				.contains("this show has been cancelled", "is being refunded"));
		assertThat(emails("BOOKING_CANCELLED")).isEmpty();
		assertThat(emails("PAYMENT_REFUNDED")).hasSize(2);

		// Gone from listings and search, still readable by id (customers' bookings point at it)
		mvc.perform(get("/api/shows")).andExpect(jsonPath("$[*].id", not(hasItem((int) showId))));
		mvc.perform(get("/api/shows/search")).andExpect(jsonPath("$.content[*].id", not(hasItem((int) showId))));
		mvc.perform(get("/api/shows/" + showId)).andExpect(jsonPath("$.status").value("CANCELLED"));
		mvc.perform(get("/api/admin/shows?when=ALL").header("Authorization", "Bearer " + admin))
				.andExpect(jsonPath("$.content[0].status").value("CANCELLED"))
				.andExpect(jsonPath("$.content[0].confirmedBookings").value(0));
		mvc.perform(get("/api/admin/shows").header("Authorization", "Bearer " + admin)) // UPCOMING: scheduled shows only
				.andExpect(jsonPath("$.totalElements").value(0));

		// And it can't be booked any more
		String late = registerAndLogin("late@example.com");
		hold(late, seats.get(5)).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Show " + showId + " has been cancelled"));
	}

	@Test
	void paymentArrivingAfterTheShowWasCancelled_isRefunded() throws Exception {
		String asha = registerAndLogin("asha@example.com");
		hold(asha, seats.get(0)).andExpect(status().isCreated());
		String orderId = PaymentApiTest.orderOf(checkout(asha, seats.get(0)));

		adminCall(post("/api/shows/" + showId + "/cancel"), null).andExpect(jsonPath("$.cancelledBookings").value(0));
		verify(asha, orderId, "pay_late").andExpect(status().isOk());

		assertThat(paymentRepository.findAll()).singleElement()
				.satisfies(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED)); // refunded right after the commit
		assertThat(bookingRepository.count()).isZero();
		assertThat(gateway.refunds).singleElement().satisfies(r -> assertThat(r.paymentId()).isEqualTo("pay_late"));
	}

	@Test
	void cancelAndPaymentsRacing_neverLeaveABookingOnACancelledShow() throws Exception {
		List<String> tokens = new ArrayList<>();
		List<String> orders = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			String token = registerAndLogin("racer" + i + "@example.com");
			hold(token, seats.get(i)).andExpect(status().isCreated());
			orders.add(PaymentApiTest.orderOf(checkout(token, seats.get(i))));
			tokens.add(token);
		}

		ExecutorService pool = Executors.newFixedThreadPool(9);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			int n = i;
			results.add(pool.submit(() -> {
				go.await();
				return verify(tokens.get(n), orders.get(n), "pay_race_" + n).andReturn().getResponse().getStatus();
			}));
		}
		results.add(pool.submit((Callable<Integer>) () -> {
			go.await();
			return adminCall(post("/api/shows/" + showId + "/cancel"), null).andReturn().getResponse().getStatus();
		}));
		go.countDown();
		for (Future<Integer> result : results) {
			assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(200);
		}
		pool.shutdown();

		// Whichever came first, every customer ends up refunded and nothing stays booked
		assertThat(bookingRepository.findAll()).noneMatch(b -> b.getStatus() == BookingStatus.CONFIRMED);
		assertThat(paymentRepository.findAll()).hasSize(8)
				.allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED));
		assertThat(gateway.refunds).hasSize(8);
	}

	@Test
	void cancelRules() throws Exception {
		String user = registerAndLogin("asha@example.com");
		mvc.perform(post("/api/shows/" + showId + "/cancel").header("Authorization", "Bearer " + user))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/shows/999999/cancel").header("Authorization", "Bearer " + admin))
				.andExpect(status().isNotFound());

		jdbc.update("update shows set start_time = now() - interval '1 minute' where id = ?", showId);
		adminCall(post("/api/shows/" + showId + "/cancel"), null).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Show " + showId + " has already started"));

		jdbc.update("update shows set start_time = now() + interval '1 day' where id = ?", showId);
		adminCall(post("/api/shows/" + showId + "/cancel"), null).andExpect(status().isOk());
		adminCall(post("/api/shows/" + showId + "/cancel"), null).andExpect(status().isConflict());
		adminCall(put("/api/shows/" + showId), showJson("Cancel Show", start, "250.00", venueId))
				.andExpect(status().isConflict());
	}

	@Test
	void editingABookedShow_rescheduleEmailsCustomers_venueIsLocked_pricesKeepTheirSnapshot() throws Exception {
		String asha = registerAndLogin("asha@example.com");
		pay(asha, "pay_a", seats.get(0));
		long otherVenue = venue("Other Venue");

		adminCall(put("/api/shows/" + showId), showJson("Cancel Show", start, "250.00", otherVenue))
				.andExpect(status().isConflict());

		Instant later = start.plus(2, ChronoUnit.HOURS);
		adminCall(put("/api/shows/" + showId), showJson("Cancel Show", later, "400.00", venueId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.price").value(400.00));

		assertThat(emails("SHOW_RESCHEDULED")).singleElement().satisfies(e -> {
			assertThat(e.getRecipient()).isEqualTo("asha@example.com");
			assertThat(e.getBodyText()).contains("has moved to a new time", "Was: ", "Now: ", "Seats: A1");
			// An updated calendar event: same UID as the one sent with the booking, the new start time
			assertThat(e.toMessage().attachments()).singleElement().satisfies(ics -> {
				assertThat(ics.filename()).endsWith(".ics");
				assertThat(new String(ics.content(), StandardCharsets.UTF_8)).contains("DTSTART:"
						+ DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(later));
			});
		});
		assertThat(bookingRepository.findAll()).singleElement()
				.satisfies(b -> assertThat(b.getTotalAmount()).isEqualByComparingTo("250.00"));

		// Same time again (only the title changes): no new emails
		adminCall(put("/api/shows/" + showId), showJson("Cancel Show (IMAX)", later, "400.00", venueId))
				.andExpect(status().isOk());
		assertThat(emails("SHOW_RESCHEDULED")).hasSize(1);
	}

	@Test
	void showWithoutBookings_canStillMoveVenue() throws Exception {
		long otherVenue = venue("Other Venue");
		adminCall(put("/api/shows/" + showId), showJson("Cancel Show", start, "250.00", otherVenue))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.venueId").value(otherVenue));
	}

	// ---- helpers ----

	List<OutboxEmail> emails(String kind) {
		return outboxRepository.findAll().stream().filter(e -> e.getKind().equals(kind)).toList();
	}

	void pay(String token, String paymentId, Integer... seatIds) throws Exception {
		hold(token, seatIds).andExpect(status().isCreated());
		String orderId = PaymentApiTest.orderOf(checkout(token, seatIds));
		verify(token, orderId, paymentId).andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	ResultActions hold(String token, Integer... seatIds) throws Exception {
		return mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(seatsJson(seatIds)));
	}

	ResultActions checkout(String token, Integer... seatIds) throws Exception {
		return mvc.perform(post("/api/bookings/checkout").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(seatsJson(seatIds)));
	}

	ResultActions verify(String token, String orderId, String paymentId) throws Exception {
		return mvc.perform(post("/api/payments/verify").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"orderId\":\"" + orderId + "\",\"paymentId\":\"" + paymentId + "\",\"signature\":\"" + sign(orderId, paymentId) + "\"}"));
	}

	String seatsJson(Integer... seatIds) {
		return "{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + "}";
	}

	ResultActions adminCall(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
		request.header("Authorization", "Bearer " + admin);
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	static String showJson(String title, Instant start, String price, long venueId) {
		return "{\"title\":\"" + title + "\",\"startTime\":\"" + start + "\",\"price\":" + price + ",\"venueId\":" + venueId + "}";
	}

	long venue(String name) throws Exception {
		return ((Number) JsonPath.read(adminCall(post("/api/venues"), "{\"name\":\"" + name + "\",\"city\":\"Pune\"}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Cancel Test\",\"email\":\"" + email + "\",\"password\":\"Cancel-User-Pw-123\"}"));
		return login(email, "Cancel-User-Pw-123");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

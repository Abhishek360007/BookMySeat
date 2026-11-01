package com.abhishekraj.BookMySeat.payment;

import com.abhishekraj.BookMySeat.TestcontainersConfig;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;
import com.abhishekraj.BookMySeat.entity.Role;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The payment flow over HTTP with payments enabled and a fake Razorpay
@SpringBootTest(properties = {
		"app.admin.email=pay-test-admin@example.com",
		"app.admin.password=Pay-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000",
		"payments.enabled=true",
		"payments.razorpay.key-id=rzp_test_fake",
		"payments.razorpay.key-secret=" + PaymentApiTest.KEY_SECRET,
		"payments.razorpay.webhook-secret=" + PaymentApiTest.WEBHOOK_SECRET
})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PaymentApiTest.FakeRazorpay.class})
class PaymentApiTest {

	static final String KEY_SECRET = "test_key_secret";
	static final String WEBHOOK_SECRET = "test_webhook_secret";

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
	@Autowired StringRedisTemplate redis;

	String admin;
	String user;
	long showId;
	List<Integer> seats;

	@BeforeEach
	void setUp() throws Exception {
		gateway.reset();
		admin = login("pay-test-admin@example.com", "Pay-Test-Admin-Pw-1");
		showId = createShowWithSeats();
		seats = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats"))
				.andReturn().getResponse().getContentAsString(), "$[*].id");
		user = registerAndLogin("pay-user1@example.com");
	}

	@AfterEach
	void cleanUp() {
		paymentRepository.deleteAll();
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
	}

	// ---- 10.1 checkout ----

	@Test
	void checkout_createsOrderForServerComputedAmount_andExtendsTheHolds() throws Exception {
		hold(user, seats.get(0), seats.get(1));

		checkout(user, seats.get(0), seats.get(1))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.orderId", startsWith("order_test_")))
				.andExpect(jsonPath("$.razorpayKeyId").value("rzp_test_fake"))
				.andExpect(jsonPath("$.amount").value(50000)) // paise
				.andExpect(jsonPath("$.currency").value("INR"))
				.andExpect(jsonPath("$.totalAmount").value(500.00));

		assertThat(gateway.orders).singleElement().satisfies(o -> assertThat(o.amountMinor()).isEqualTo(50000));
		assertThat(paymentRepository.findAll()).singleElement()
				.satisfies(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING));
		// Held for the 15-minute payment window (+1 min grace), not the normal 5 minutes
		assertThat(redis.getExpire("hold:show:" + showId + ":seat:" + seats.get(0))).isGreaterThan(900);
	}

	@Test
	void checkoutRetry_returnsTheSameOpenOrder() throws Exception {
		hold(user, seats.get(0));
		String first = JsonPath.read(checkout(user, seats.get(0)).andReturn().getResponse().getContentAsString(), "$.orderId");

		checkout(user, seats.get(0)).andExpect(status().isCreated()).andExpect(jsonPath("$.orderId").value(first));

		assertThat(gateway.orders).hasSize(1);
	}

	@Test
	void checkoutWithoutHolding_is409() throws Exception {
		checkout(user, seats.get(0)).andExpect(status().isConflict());

		String other = registerAndLogin("pay-user2@example.com");
		hold(other, seats.get(1));
		checkout(user, seats.get(1)).andExpect(status().isConflict()); // held by someone else
		assertThat(gateway.orders).isEmpty();
	}

	@Test
	void directConfirmWithoutPaying_isRefused() throws Exception {
		hold(user, seats.get(0));

		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + user)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"showId\":" + showId + ",\"seatIds\":[" + seats.get(0) + "]}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail", startsWith("Payment required")));
		assertThat(bookingRepository.count()).isZero();
	}

	// ---- 10.2 verify (client callback) ----

	@Test
	void validPayment_createsTheBooking_releasesHolds_andIsIdempotent() throws Exception {
		hold(user, seats.get(0), seats.get(1));
		String orderId = orderOf(checkout(user, seats.get(0), seats.get(1)));

		String first = verify(user, orderId, "pay_test_1", sign(orderId, "pay_test_1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.paymentId").value("pay_test_1"))
				.andExpect(jsonPath("$.bookingId").isNumber())
				.andReturn().getResponse().getContentAsString();

		// Repeating the callback changes nothing
		verify(user, orderId, "pay_test_1", sign(orderId, "pay_test_1"))
				.andExpect(jsonPath("$.bookingId").value((Integer) JsonPath.read(first, "$.bookingId")));

		mvc.perform(get("/api/bookings/me").header("Authorization", "Bearer " + user))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].status").value("CONFIRMED"))
				.andExpect(jsonPath("$[0].totalAmount").value(500.00));
		assertThat(redis.keys("hold:show:" + showId + ":*")).isEmpty();
		mvc.perform(get("/api/payments/" + orderId).header("Authorization", "Bearer " + user))
				.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	@Test
	void tamperedSignature_is400_andBooksNothing() throws Exception {
		hold(user, seats.get(0));
		String orderId = orderOf(checkout(user, seats.get(0)));

		verify(user, orderId, "pay_test_2", sign(orderId, "pay_test_OTHER"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid signature"));

		assertThat(bookingRepository.count()).isZero();
		assertThat(paymentRepository.findAll()).singleElement()
				.satisfies(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING));
	}

	@Test
	void someoneElsesOrder_is404() throws Exception {
		hold(user, seats.get(0));
		String orderId = orderOf(checkout(user, seats.get(0)));
		String other = registerAndLogin("pay-user2@example.com");

		verify(other, orderId, "pay_test_3", sign(orderId, "pay_test_3")).andExpect(status().isNotFound());
		mvc.perform(get("/api/payments/" + orderId).header("Authorization", "Bearer " + other))
				.andExpect(status().isNotFound());
	}

	@Test
	void bookingRecordsWhatWasPaid_evenIfThePriceChangesMeanwhile() throws Exception {
		hold(user, seats.get(0));
		String orderId = orderOf(checkout(user, seats.get(0))); // 250.00

		Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		mvc.perform(put("/api/shows/" + showId)
				.header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Pay Show\",\"startTime\":\"" + start + "\",\"price\":400.00,\"venueId\":"
						+ showRepository.findById(showId).orElseThrow().getVenue().getId() + "}"))
				.andExpect(status().isOk());

		verify(user, orderId, "pay_test_4", sign(orderId, "pay_test_4")).andExpect(jsonPath("$.status").value("CONFIRMED"));
		mvc.perform(get("/api/bookings/me").header("Authorization", "Bearer " + user))
				.andExpect(jsonPath("$[0].totalAmount").value(250.00));
	}

	// ---- 10.3 webhook ----

	@Test
	void capturedWebhook_books_evenIfTheClientNeverCallsBack() throws Exception {
		hold(user, seats.get(0));
		String orderId = orderOf(checkout(user, seats.get(0)));

		webhook("evt_1", "payment.captured", orderId, "pay_wh_1")
				.andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PROCESSED"));

		assertThat(paymentRepository.findAll()).singleElement().satisfies(p -> {
			assertThat(p.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
			assertThat(p.getPaymentId()).isEqualTo("pay_wh_1");
		});
		assertThat(bookingRepository.count()).isEqualTo(1);
	}

	@Test
	void redeliveredAndOverlappingEvents_bookOnce() throws Exception {
		hold(user, seats.get(0));
		String orderId = orderOf(checkout(user, seats.get(0)));

		webhook("evt_2", "payment.captured", orderId, "pay_wh_2").andExpect(jsonPath("$.result").value("PROCESSED"));
		webhook("evt_2", "payment.captured", orderId, "pay_wh_2").andExpect(jsonPath("$.result").value("DUPLICATE"));
		webhook("evt_3", "order.paid", orderId, "pay_wh_2").andExpect(jsonPath("$.result").value("PROCESSED"));

		assertThat(bookingRepository.count()).isEqualTo(1);
		assertThat(gateway.refunds).isEmpty();
	}

	@Test
	void callbackAndWebhooksRacing_produceExactlyOneBooking() throws Exception {
		hold(user, seats.get(0), seats.get(1));
		String orderId = orderOf(checkout(user, seats.get(0), seats.get(1)));
		String signature = sign(orderId, "pay_race");

		int reports = 10;
		ExecutorService pool = Executors.newFixedThreadPool(reports);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < reports; i++) {
			int n = i;
			results.add(pool.submit(() -> {
				go.await();
				ResultActions r = n % 2 == 0
						? webhook("evt_race_" + n, "payment.captured", orderId, "pay_race")
						: verify(user, orderId, "pay_race", signature);
				return r.andReturn().getResponse().getStatus();
			}));
		}
		go.countDown();
		for (Future<Integer> result : results) {
			assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
		}
		pool.shutdown();

		assertThat(bookingRepository.count()).isEqualTo(1);
		assertThat(gateway.refunds).isEmpty();
		assertThat(paymentRepository.findAll()).singleElement()
				.satisfies(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.CONFIRMED));
	}

	@Test
	void forgedWebhook_is400() throws Exception {
		hold(user, seats.get(0));
		String orderId = orderOf(checkout(user, seats.get(0)));
		byte[] body = webhookBody("payment.captured", orderId, "pay_forged");

		mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
						.header("X-Razorpay-Event-Id", "evt_forged")
						.header("X-Razorpay-Signature", hmac("not-the-webhook-secret", body)))
				.andExpect(status().isBadRequest());
		assertThat(bookingRepository.count()).isZero();
	}

	@Test
	void failedAttempt_keepsTheOrderOpenForARetry() throws Exception {
		hold(user, seats.get(0));
		String orderId = orderOf(checkout(user, seats.get(0)));

		webhook("evt_fail", "payment.failed", orderId, "pay_failed").andExpect(jsonPath("$.result").value("PROCESSED"));
		assertThat(paymentRepository.findAll()).singleElement().satisfies(p -> {
			assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
			assertThat(p.getFailureReason()).isEqualTo("Card declined");
		});

		// The customer tries again on the same order and succeeds
		verify(user, orderId, "pay_retry", sign(orderId, "pay_retry")).andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	@Test
	void webhookForAnUnknownOrder_isAcknowledgedAndIgnored() throws Exception {
		webhook("evt_unknown", "payment.captured", "order_not_ours", "pay_x")
				.andExpect(status().isOk()).andExpect(jsonPath("$.result").value("IGNORED"));
	}

	// ---- helpers ----

	ResultActions webhook(String eventId, String event, String orderId, String paymentId) throws Exception {
		byte[] body = webhookBody(event, orderId, paymentId);
		return mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Razorpay-Event-Id", eventId)
				.header("X-Razorpay-Signature", hmac(WEBHOOK_SECRET, body)));
	}

	// Shape of a Razorpay payment event (trimmed to the fields the app reads)
	static byte[] webhookBody(String event, String orderId, String paymentId) {
		return ("{\"entity\":\"event\",\"event\":\"" + event + "\",\"payload\":{\"payment\":{\"entity\":{"
				+ "\"id\":\"" + paymentId + "\",\"order_id\":\"" + orderId + "\",\"amount\":25000,\"currency\":\"INR\","
				+ "\"error_description\":\"Card declined\"}}}}").getBytes(StandardCharsets.UTF_8);
	}

	// ---- helpers (shared) ----

	ResultActions verify(String token, String orderId, String paymentId, String signature) throws Exception {
		return mvc.perform(post("/api/payments/verify").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"orderId\":\"" + orderId + "\",\"paymentId\":\"" + paymentId + "\",\"signature\":\"" + signature + "\"}"));
	}

	static String orderOf(ResultActions checkout) throws Exception {
		return JsonPath.read(checkout.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.orderId");
	}

	// Razorpay's documented scheme, computed independently of the app's own code
	static String sign(String orderId, String paymentId) throws Exception {
		return hmac(KEY_SECRET, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
	}

	static String hmac(String secret, byte[] data) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(data));
	}

	ResultActions checkout(String token, Integer... seatIds) throws Exception {
		return mvc.perform(post("/api/bookings/checkout").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + "}"));
	}

	void hold(String token, Integer... seatIds) throws Exception {
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + "}"))
				.andExpect(status().isCreated());
	}

	String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Pay Test\",\"email\":\"" + email + "\",\"password\":\"Pay-User-Pw-123\"}"));
		return login(email, "Pay-User-Pw-123");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}

	long createShowWithSeats() throws Exception {
		int venueId = JsonPath.read(mvc.perform(post("/api/venues").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pay Venue\",\"city\":\"Paytown\"}"))
				.andReturn().getResponse().getContentAsString(), "$.id");
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		int id = JsonPath.read(mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Pay Show\",\"startTime\":\"" + start + "\",\"price\":250.00,\"venueId\":" + venueId + "}"))
				.andReturn().getResponse().getContentAsString(), "$.id");
		mvc.perform(post("/api/shows/" + id + "/seats").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"rows\":1,\"seatsPerRow\":5}"));
		return id;
	}
}

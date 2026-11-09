package com.abhishekraj.BookMySeat.payment;

import com.abhishekraj.BookMySeat.TestcontainersConfig;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.notification.OutboxEmail;
import com.abhishekraj.BookMySeat.notification.OutboxEmailRepository;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.PaymentRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.abhishekraj.BookMySeat.payment.PaymentApiTest.KEY_SECRET;
import static com.abhishekraj.BookMySeat.payment.PaymentApiTest.WEBHOOK_SECRET;
import static com.abhishekraj.BookMySeat.payment.PaymentApiTest.hmac;
import static com.abhishekraj.BookMySeat.payment.PaymentApiTest.orderOf;
import static com.abhishekraj.BookMySeat.payment.PaymentApiTest.sign;
import static com.abhishekraj.BookMySeat.payment.PaymentApiTest.webhookBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Promo codes with online payment: the use is reserved for the Razorpay order, kept when it is paid, and given back
// when it expires or the money goes back. Same configuration as PaymentApiTest (shared Spring context).
@SpringBootTest(properties = {
		"app.admin.email=pay-test-admin@example.com",
		"app.admin.password=Pay-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000",
		"payments.enabled=true",
		"payments.razorpay.key-id=rzp_test_fake",
		"payments.razorpay.key-secret=" + KEY_SECRET,
		"payments.razorpay.webhook-secret=" + WEBHOOK_SECRET
})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PaymentApiTest.FakeRazorpay.class})
class PromoCodePaymentApiTest {

	@Autowired MockMvc mvc;
	@Autowired FakePaymentGateway gateway;
	@Autowired PaymentRepository paymentRepository;
	@Autowired PromoCodeRepository promoCodeRepository;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired PaymentMaintenanceJob maintenanceJob;
	@Autowired JdbcTemplate jdbc;
	@Autowired OutboxEmailRepository outboxRepository;

	String admin;
	String user;
	long showId;
	List<Integer> seats;

	@BeforeEach
	void setUp() throws Exception {
		gateway.reset();
		admin = login("pay-test-admin@example.com", "Pay-Test-Admin-Pw-1");
		int venueId = JsonPath.read(mvc.perform(post("/api/venues").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Promo Pay Venue\",\"city\":\"Paytown\"}"))
				.andReturn().getResponse().getContentAsString(), "$.id");
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		showId = ((Number) JsonPath.read(mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Promo Pay Show\",\"startTime\":\"" + start + "\",\"price\":250.00,\"venueId\":" + venueId + "}"))
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		mvc.perform(post("/api/shows/" + showId + "/seats").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"rows\":1,\"seatsPerRow\":4}")).andExpect(status().isCreated());
		seats = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats")).andReturn().getResponse().getContentAsString(), "$[*].id");
		user = registerAndLogin("promo-pay-user@example.com");
	}

	@AfterEach
	void cleanUp() {
		promoCodeRepository.deleteAll();
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
	void checkout_chargesTheDiscountedAmount_andThePaidBookingKeepsTheCode() throws Exception {
		long code = createCode("{\"code\":\"PAY20\",\"kind\":\"PERCENT\",\"amount\":20}");
		hold(user, seats.get(0), seats.get(1));

		String orderId = orderOf(checkout(user, "pay20", seats.get(0), seats.get(1))
				.andExpect(jsonPath("$.amount").value(40000)) // 500 - 20 %, in paise
				.andExpect(jsonPath("$.totalAmount").value(400.00))
				.andExpect(jsonPath("$.discountAmount").value(100.00))
				.andExpect(jsonPath("$.promoCode").value("PAY20")));
		assertThat(gateway.orders).singleElement().satisfies(o -> assertThat(o.amountMinor()).isEqualTo(40000));
		assertThat(uses(code)).isEqualTo(1);
		assertThat(redemption(orderId)).isEqualTo("RESERVED");

		verify(user, orderId, "pay_promo_1").andExpect(jsonPath("$.status").value("CONFIRMED"));
		assertThat(redemption(orderId)).isEqualTo("REDEEMED");
		assertThat(uses(code)).isEqualTo(1);
		mvc.perform(get("/api/bookings/me").header("Authorization", "Bearer " + user))
				.andExpect(jsonPath("$[0].totalAmount").value(400.00))
				.andExpect(jsonPath("$[0].discountAmount").value(100.00))
				.andExpect(jsonPath("$[0].promoCode").value("PAY20"));
		mvc.perform(get("/api/admin/shows/" + showId + "/bookings").header("Authorization", "Bearer " + admin))
				.andExpect(jsonPath("$.content[0].totalAmount").value(400.00))
				.andExpect(jsonPath("$.content[0].discountAmount").value(100.00))
				.andExpect(jsonPath("$.content[0].promoCode").value("PAY20"));
		assertThat(emails("BOOKING_CONFIRMED")).singleElement().satisfies(e -> {
			assertThat(e.getBodyText()).contains("Total: ₹400.00 (₹100.00 off with PAY20)");
			assertThat(e.getBodyHtml()).contains("₹400.00 (₹100.00 off with PAY20)");
		});
	}

	@Test
	void aRefusedCode_isA422_beforeAnyOrderExists() throws Exception {
		createCode("{\"code\":\"SPENT\",\"kind\":\"FLAT\",\"amount\":50,\"maxUses\":1}");
		String other = registerAndLogin("promo-pay-other@example.com");
		hold(other, seats.get(1));
		orderOf(checkout(other, "SPENT", seats.get(1))); // takes the only use (reserved)
		int ordersBefore = gateway.orders.size();

		hold(user, seats.get(0));
		checkout(user, "SPENT", seats.get(0)).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.detail").value("This code has been fully used"));
		checkout(user, "NOSUCH", seats.get(0)).andExpect(status().isUnprocessableContent());
		assertThat(gateway.orders).hasSize(ordersBefore);
		checkout(user, null, seats.get(0)).andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(25000));
	}

	@Test
	void retryingWithTheSameCode_reusesTheOrder_anotherCodeMakesANewOne() throws Exception {
		createCode("{\"code\":\"RETRY10\",\"kind\":\"FLAT\",\"amount\":10}");
		createCode("{\"code\":\"RETRY30\",\"kind\":\"FLAT\",\"amount\":30}");
		hold(user, seats.get(0));

		String first = orderOf(checkout(user, "RETRY10", seats.get(0)));
		checkout(user, "retry10", seats.get(0)).andExpect(jsonPath("$.orderId").value(first));
		assertThat(gateway.orders).hasSize(1);

		String second = orderOf(checkout(user, "RETRY30", seats.get(0)).andExpect(jsonPath("$.totalAmount").value(220.00)));
		assertThat(second).isNotEqualTo(first);
		String third = orderOf(checkout(user, null, seats.get(0)).andExpect(jsonPath("$.totalAmount").value(250.00)));
		assertThat(third).isNotIn(first, second);
	}

	@Test
	void anUnpaidOrder_givesItsUseBack_whenItExpires() throws Exception {
		long code = createCode("{\"code\":\"EXPIRES\",\"kind\":\"FLAT\",\"amount\":50,\"maxUses\":1}");
		hold(user, seats.get(0));
		String orderId = orderOf(checkout(user, "EXPIRES", seats.get(0)));
		assertThat(uses(code)).isEqualTo(1);

		expire(orderId);

		assertThat(uses(code)).isEqualTo(0);
		assertThat(redemption(orderId)).isEqualTo("RELEASED");
		String other = registerAndLogin("promo-pay-next@example.com");
		hold(other, seats.get(1));
		checkout(other, "EXPIRES", seats.get(1)).andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(200.00));
	}

	@Test
	void aLatePayment_takesTheUseAgain_whenThereIsRoom() throws Exception {
		long code = createCode("{\"code\":\"LATEOK\",\"kind\":\"FLAT\",\"amount\":50,\"maxUses\":1}");
		hold(user, seats.get(0));
		String orderId = orderOf(checkout(user, "LATEOK", seats.get(0)));
		expire(orderId);

		verify(user, orderId, "pay_late_ok").andExpect(jsonPath("$.status").value("CONFIRMED"));

		assertThat(uses(code)).isEqualTo(1);
		assertThat(redemption(orderId)).isEqualTo("REDEEMED");
		assertThat(gateway.refunds).isEmpty();
	}

	@Test
	void aLatePayment_isRefunded_whenTheCodeWasUsedUpMeanwhile() throws Exception {
		long code = createCode("{\"code\":\"LATEFULL\",\"kind\":\"FLAT\",\"amount\":50,\"maxUses\":1}");
		hold(user, seats.get(0));
		String lateOrder = orderOf(checkout(user, "LATEFULL", seats.get(0)));
		expire(lateOrder);
		String other = registerAndLogin("promo-pay-quick@example.com");
		hold(other, seats.get(1));
		String otherOrder = orderOf(checkout(other, "LATEFULL", seats.get(1)));
		verify(other, otherOrder, "pay_quick").andExpect(jsonPath("$.status").value("CONFIRMED"));

		webhook("evt_promo_late", lateOrder, "pay_late_full").andExpect(jsonPath("$.result").value("PROCESSED"));

		mvc.perform(get("/api/payments/" + lateOrder).header("Authorization", "Bearer " + user))
				.andExpect(jsonPath("$.status").value("REFUNDED"))
				.andExpect(jsonPath("$.bookingId").doesNotExist());
		assertThat(gateway.refunds).singleElement().satisfies(r -> {
			assertThat(r.paymentId()).isEqualTo("pay_late_full");
			assertThat(r.amountMinor()).isEqualTo(20000); // what was paid: 250 - 50
		});
		assertThat(uses(code)).isEqualTo(1); // only the other customer's
		assertThat(bookingRepository.count()).isEqualTo(1);
	}

	@Test
	void aPaymentWhoseSeatsAreGone_isRefunded_andGivesTheUseBack() throws Exception {
		long code = createCode("{\"code\":\"GONE\",\"kind\":\"FLAT\",\"amount\":25}");
		hold(user, seats.get(0));
		String lateOrder = orderOf(checkout(user, "GONE", seats.get(0)));
		expire(lateOrder);
		String other = registerAndLogin("promo-pay-taker@example.com");
		hold(other, seats.get(0));
		String otherOrder = orderOf(checkout(other, null, seats.get(0)));
		verify(other, otherOrder, "pay_taker").andExpect(jsonPath("$.status").value("CONFIRMED"));

		webhook("evt_promo_gone", lateOrder, "pay_gone").andExpect(status().isOk());

		assertThat(statusOf(lateOrder)).isEqualTo(PaymentStatus.REFUNDED);
		assertThat(gateway.refunds).singleElement().satisfies(r -> assertThat(r.amountMinor()).isEqualTo(22500));
		assertThat(uses(code)).isEqualTo(0);
		assertThat(redemption(lateOrder)).isEqualTo("RELEASED");
	}

	@Test
	void cancellingAPaidBooking_refundsWhatWasPaid_andGivesTheUseBack() throws Exception {
		long code = createCode("{\"code\":\"CANCELME\",\"kind\":\"FLAT\",\"amount\":100}");
		hold(user, seats.get(0));
		String orderId = orderOf(checkout(user, "CANCELME", seats.get(0)));
		int bookingId = JsonPath.read(verify(user, orderId, "pay_cancel_promo")
				.andReturn().getResponse().getContentAsString(), "$.bookingId");

		mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", "Bearer " + user))
				.andExpect(status().isOk());

		assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.REFUNDED);
		assertThat(gateway.refunds).singleElement().satisfies(r -> assertThat(r.amountMinor()).isEqualTo(15000));
		assertThat(uses(code)).isEqualTo(0);
		assertThat(redemption(orderId)).isEqualTo("RELEASED");
	}

	// ---- helpers ----

	int uses(long codeId) {
		return jdbc.queryForObject("select uses from promo_codes where id = ?", Integer.class, codeId);
	}

	String redemption(String orderId) {
		return jdbc.queryForObject("select r.status from promo_redemptions r join payments p on p.id = r.payment_id "
				+ "where p.order_id = ?", String.class, orderId);
	}

	List<OutboxEmail> emails(String kind) {
		return outboxRepository.findAll().stream().filter(e -> e.getKind().equals(kind)).toList();
	}

	PaymentStatus statusOf(String orderId) {
		return paymentRepository.findAll().stream().filter(p -> p.getOrderId().equals(orderId)).findFirst().orElseThrow().getStatus();
	}

	void expire(String orderId) {
		jdbc.update("update payments set expires_at = now() - interval '1 minute' where order_id = ?", orderId);
		jdbc.update("update shedlock set lock_until = timestamp '2000-01-01 00:00:00' where name = 'paymentMaintenance'");
		maintenanceJob.run();
	}

	long createCode(String body) throws Exception {
		return ((Number) JsonPath.read(mvc.perform(post("/api/admin/promo-codes").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	ResultActions checkout(String token, String code, Integer... seatIds) throws Exception {
		String promo = code == null ? "" : ",\"promoCode\":\"" + code + "\"";
		return mvc.perform(post("/api/bookings/checkout").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + promo + "}"));
	}

	ResultActions verify(String token, String orderId, String paymentId) throws Exception {
		return mvc.perform(post("/api/payments/verify").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"orderId\":\"" + orderId + "\",\"paymentId\":\"" + paymentId + "\",\"signature\":\""
						+ sign(orderId, paymentId) + "\"}"));
	}

	ResultActions webhook(String eventId, String orderId, String paymentId) throws Exception {
		byte[] body = webhookBody("payment.captured", orderId, paymentId);
		return mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Razorpay-Event-Id", eventId)
				.header("X-Razorpay-Signature", hmac(WEBHOOK_SECRET, body)));
	}

	void hold(String token, Integer... seatIds) throws Exception {
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + "}"))
				.andExpect(status().isCreated());
	}

	String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Promo Payer\",\"email\":\"" + email + "\",\"password\":\"Pay-User-Pw-123\"}"));
		return login(email, "Pay-User-Pw-123");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

package com.abhishekraj.BookMySeat.notification;

import com.abhishekraj.BookMySeat.TestcontainersConfig;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.abhishekraj.BookMySeat.ticket.QrDecoder;
import com.abhishekraj.BookMySeat.ticket.TicketCodes;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Booking emails end to end: the API change queues the email, and it is delivered right after the commit
@SpringBootTest(properties = {
		"app.admin.email=mail-test-admin@example.com",
		"app.admin.password=Mail-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000",
		"email.public-url=https://bookmyseat.test/",
		"email.outbox.initial-delay=PT1H" // only the after-commit send delivers here
})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, BookingEmailTest.FakeEmail.class})
class BookingEmailTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeEmail {
		@Bean
		@Primary
		FakeEmailSender fakeEmailSender() {
			return new FakeEmailSender();
		}
	}

	static final String GUEST = "mail-guest@example.com";

	@Autowired MockMvc mvc;
	@Autowired FakeEmailSender sender;
	@Autowired OutboxEmailRepository outboxRepository;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired TicketCodes ticketCodes;

	String admin;
	String user;
	long showId;
	List<Integer> seats;

	@BeforeEach
	void setUp() throws Exception {
		sender.reset(); // a previous test may have left the provider "down"
		admin = login("mail-test-admin@example.com", "Mail-Test-Admin-Pw-1");
		// A title with HTML in it: the HTML email must escape it
		showId = createShow("Dune <Part Three> & More");
		seats = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats"))
				.andReturn().getResponse().getContentAsString(), "$[*].id");
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Asha\",\"email\":\"" + GUEST + "\",\"password\":\"Mail-User-Pw-123\"}"));
		user = login(GUEST, "Mail-User-Pw-123");
		awaitQuiet();
		sender.reset();
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
	void confirmingABooking_emailsTheDetails() throws Exception {
		int bookingId = book(seats.get(0), seats.get(1));

		await(() -> !sender.sentTo(GUEST).isEmpty());
		EmailMessage email = sender.sentTo(GUEST).getFirst();
		assertThat(email.subject()).isEqualTo("Booking confirmed: Dune <Part Three> & More");
		assertThat(email.text())
				.contains("Hi Asha", "Booking #" + bookingId, "Seats: A1, A2", "Total: ₹500.00",
						"When: Fri 31 Dec 2027, 9:30 pm", "https://bookmyseat.test/#/bookings");
		assertThat(email.html())
				.contains("Dune &lt;Part Three&gt; &amp; More", "<b>A1, A2</b>", "href=\"https://bookmyseat.test/#/bookings\"")
				.doesNotContain("<Part Three>");
		// The sender sees the email before the outbox's own transaction commits it as SENT: wait for that commit
		await(() -> outboxRepository.findAll().stream().anyMatch(e -> e.getStatus() == OutboxEmail.Status.SENT));
		assertThat(outboxRepository.findAll()).singleElement().satisfies(e -> {
			assertThat(e.getKind()).isEqualTo("BOOKING_CONFIRMED");
			assertThat(e.getStatus()).isEqualTo(OutboxEmail.Status.SENT);
		});

		// The e-ticket rides along: its QR decodes to this booking's signed ticket code
		String ticketCode = ticketCodes.codeFor(bookingId);
		assertThat(email.text()).contains("Ticket code: " + ticketCode);
		// Both attachments went through the outbox table (the sender got them back from it), in order
		assertThat(email.attachments()).hasSize(2);
		EmailMessage.Attachment ticket = email.attachments().get(0);
		assertThat(ticket.filename()).isEqualTo("ticket-" + bookingId + ".png");
		assertThat(ticket.contentType()).isEqualTo("image/png");
		assertThat(QrDecoder.decode(ticket.content())).isEqualTo(ticketCode);

		// ...and the show as a calendar event, mentioned in the email
		EmailMessage.Attachment event = email.attachments().get(1);
		assertThat(event.filename()).isEqualTo("booking-" + bookingId + ".ics");
		assertThat(event.contentType()).isEqualTo("text/calendar; charset=UTF-8");
		assertThat(new String(event.content(), StandardCharsets.UTF_8).replace("\r\n ", ""))
				.contains("UID:booking-" + bookingId + "@bookmyseat\r\n", "DTSTART:20271231T160000Z\r\n",
						"SUMMARY:Dune <Part Three> & More\r\n", "Ticket code: " + ticketCode);
		assertThat(email.text()).contains("open the attached booking-" + bookingId + ".ics");
	}

	@Test
	void failedBooking_sendsNothing() throws Exception {
		// Never held: the confirm is refused and its transaction rolls back
		confirm(seats.get(0)).andExpect(status().isConflict());

		Thread.sleep(300);
		assertThat(sender.calls).hasValue(0);
		assertThat(outboxRepository.count()).isZero();
	}

	@Test
	void cancellingABooking_emailsTheCancellation() throws Exception {
		int bookingId = book(seats.get(0));
		await(() -> sender.sentTo(GUEST).size() == 1);

		mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", "Bearer " + user))
				.andExpect(status().isOk());

		await(() -> sender.sentTo(GUEST).size() == 2);
		EmailMessage email = sender.sentTo(GUEST).get(1);
		assertThat(email.subject()).isEqualTo("Booking cancelled: Dune <Part Three> & More");
		assertThat(email.text()).contains("Booking #" + bookingId + " is cancelled", "Seats: A1")
				.doesNotContain("refunded"); // not paid online, so nothing to refund
	}

	@Test
	void providerDown_keepsTheEmailForTheJob() throws Exception {
		sender.fail = true;
		book(seats.get(0));

		await(() -> sender.calls.get() == 1);
		await(() -> outboxRepository.findAll().stream().anyMatch(e -> e.getAttempts() == 1));
		assertThat(outboxRepository.findAll()).singleElement().satisfies(e -> {
			assertThat(e.getStatus()).isEqualTo(OutboxEmail.Status.PENDING);
			assertThat(e.getNextAttemptAt()).isAfter(Instant.now()); // the job retries it later
		});
		assertThat(bookingRepository.count()).isEqualTo(1); // the booking itself is unaffected
	}

	// ---- helpers ----

	int book(Integer... seatIds) throws Exception {
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + user)
						.contentType(MediaType.APPLICATION_JSON).content(body(seatIds)))
				.andExpect(status().isCreated());
		return JsonPath.read(confirm(seatIds).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id");
	}

	ResultActions confirm(Integer... seatIds) throws Exception {
		return mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + user)
				.contentType(MediaType.APPLICATION_JSON).content(body(seatIds)));
	}

	String body(Integer... seatIds) {
		return "{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + "}";
	}

	static void await(BooleanSupplier condition) throws InterruptedException {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (!condition.getAsBoolean()) {
			assertThat(Instant.now()).as("condition not met within 10 s").isBefore(deadline);
			Thread.sleep(25);
		}
	}

	/** Lets after-commit sends from setUp (e.g. future registration emails) finish before a test starts counting. */
	void awaitQuiet() throws InterruptedException {
		await(() -> outboxRepository.findAll().stream().noneMatch(e -> e.getStatus() == OutboxEmail.Status.PENDING));
		outboxRepository.deleteAll();
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}

	long createShow(String title) throws Exception {
		int venueId = JsonPath.read(mvc.perform(post("/api/venues").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mail Venue\",\"city\":\"Pune\"}"))
				.andReturn().getResponse().getContentAsString(), "$.id");
		// 9:30 pm in India
		Instant start = LocalDateTime.of(2027, 12, 31, 21, 30).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
		int id = JsonPath.read(mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"" + title + "\",\"startTime\":\"" + start + "\",\"price\":250.00,\"venueId\":" + venueId + "}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		mvc.perform(post("/api/shows/" + id + "/seats").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"rows\":2,\"seatsPerRow\":5}"))
				.andExpect(status().isCreated());
		return id;
	}
}

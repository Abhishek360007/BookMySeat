package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.notification.OutboxEmail;
import com.abhishekraj.BookMySeat.notification.OutboxEmailRepository;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.ReviewRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.abhishekraj.BookMySeat.service.ReviewRequests;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "How was it?" emails: who is asked, when, and only once. The job itself is kept out of the way for every test
// (reviews.request.initial-delay in the pom); here it is run by hand. Same configuration as BookingApiTest.
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ReviewRequestTest {

	@Autowired MockMvc mvc;
	@Autowired ReviewRequests reviewRequests;
	@Autowired OutboxEmailRepository outboxRepository;
	@Autowired BookingRepository bookingRepository;
	@Autowired ReviewRepository reviewRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired JdbcTemplate jdbc;
	@Value("${app.demo.email}") String demoEmail;

	String admin;
	long venueId;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		venueId = ((Number) JsonPath.read(adminCall(post("/api/venues"), "{\"name\":\"Ask Venue\",\"city\":\"Pune\"}")
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		outboxRepository.deleteAll();
	}

	@AfterEach
	void cleanUp() {
		outboxRepository.deleteAll();
		reviewRepository.deleteAll();
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		// Films outlive their shows; these tests count ratings, so start each one from fresh films
		jdbc.update("delete from films f where not exists (select 1 from shows s where s.film_id = f.id)");
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
	}

	@Test
	void peopleWhoWent_areAskedOnce_afterTheShow() throws Exception {
		long over = showWithSeats("Over Show");
		long running = showWithSeats("Running Show");
		long longAgo = showWithSeats("Long Ago Show");
		long cancelled = showWithSeats("Cancelled Show");

		String asha = registerAndLogin("ask-asha@example.com", "Asha");
		String ravi = registerAndLogin("ask-ravi@example.com", "Ravi");
		String meera = registerAndLogin("ask-meera@example.com", "Meera");
		String guest = registerAndLogin("ask-guest@example.com", "Guest");
		book(asha, over, 0);
		book(asha, over, 1); // two bookings for the same show: asked once
		book(ravi, over, 2);
		int meeraBooking = book(meera, over, 3);
		book(guest, over, 4);
		book(asha, running, 0);
		book(asha, longAgo, 0);
		int guestCancelled = book(guest, cancelled, 0);
		mvc.perform(post("/api/bookings/" + guestCancelled + "/cancel").header("Authorization", "Bearer " + guest))
				.andExpect(status().isOk());
		book(meera, cancelled, 1);
		adminCall(post("/api/shows/" + cancelled + "/cancel"), "").andExpect(status().isOk());
		outboxRepository.deleteAll(); // the confirmation and cancellation emails

		startedAgo(over, "4 hours");
		startedAgo(running, "1 hour");    // not over yet (the film runs 3 hours, the default)
		startedAgo(longAgo, "8 days");    // outside the 7-day window
		startedAgo(cancelled, "4 hours");
		// Ravi already reviewed the show on the night
		mvc.perform(put("/api/shows/" + over + "/reviews/me").header("Authorization", "Bearer " + ravi)
				.contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4}")).andExpect(status().isCreated());

		assertThat(reviewRequests.sendDue()).isEqualTo(3); // Asha, Meera and the guest about "Over Show"
		List<OutboxEmail> emails = outboxRepository.findAll().stream().filter(e -> e.getKind().equals("REVIEW_REQUEST")).toList();
		assertThat(emails).extracting(OutboxEmail::getRecipient)
				.containsExactlyInAnyOrder("ask-asha@example.com", "ask-meera@example.com", "ask-guest@example.com");
		OutboxEmail toMeera = emails.stream().filter(e -> e.getRecipient().equals("ask-meera@example.com")).findFirst().orElseThrow();
		assertThat(toMeera.getSubject()).isEqualTo("How was Over Show?");
		long overFilm = ((Number) JsonPath.read(mvc.perform(get("/api/shows/" + over)).andReturn().getResponse()
				.getContentAsString(), "$.filmId")).longValue();
		assertThat(toMeera.getBodyText()).contains("Hi Meera", "Over Show at Ask Venue", "/#/film/" + overFilm + "/review");
		assertThat(toMeera.getBodyHtml()).contains("href=\"http://localhost:8080/#/film/" + overFilm + "/review\"");

		// Every booking of the show was looked at once, Ravi's too; nothing for the others
		assertThat(jdbc.queryForObject("select count(*) from bookings where show_id = ? and review_requested_at is not null",
				Integer.class, over)).isEqualTo(5);
		assertThat(jdbc.queryForObject("select count(*) from bookings where show_id <> ? and review_requested_at is not null",
				Integer.class, over)).isZero();
		assertThat(jdbc.queryForObject("select review_requested_at from bookings where id = ?", Instant.class, meeraBooking))
				.isNotNull();

		// A second run asks nobody again
		assertThat(reviewRequests.sendDue()).isZero();
		// Once the running show is over, its people are asked
		startedAgo(running, "5 hours");
		assertThat(reviewRequests.sendDue()).isEqualTo(1);
	}

	@Test
	void theDemoAccount_isNeverEmailed() throws Exception {
		// email.suppressed-recipients holds the shared demo account's address (app.demo.email)
		long show = showWithSeats("Demo Show");
		String demo = registerAndLogin(demoEmail, "Demo");
		book(demo, show, 0);
		startedAgo(show, "4 hours");

		reviewRequests.sendDue();
		assertThat(outboxRepository.findAll().stream().filter(e -> e.getKind().equals("REVIEW_REQUEST"))).isEmpty();
	}

	void startedAgo(long showId, String interval) {
		jdbc.update("update shows set start_time = now() - interval '" + interval + "' where id = ?", showId);
	}

	long showWithSeats(String title) throws Exception {
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		long id = ((Number) JsonPath.read(adminCall(post("/api/shows"), "{\"title\":\"" + title + "\",\"startTime\":\"" + start
				+ "\",\"price\":200.00,\"venueId\":" + venueId + "}").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		adminCall(post("/api/shows/" + id + "/seats"), "{\"rows\":1,\"seatsPerRow\":6}").andExpect(status().isCreated());
		return id;
	}

	int book(String token, long showId, int seatIndex) throws Exception {
		List<Integer> seats = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats")).andReturn().getResponse()
				.getContentAsString(), "$[*].id");
		String body = "{\"showId\":" + showId + ",\"seatIds\":[" + seats.get(seatIndex) + "]}";
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		return JsonPath.read(mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	MockHttpServletRequestBuilder withAdmin(MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer " + admin);
	}

	org.springframework.test.web.servlet.ResultActions adminCall(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(withAdmin(request).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	String registerAndLogin(String email, String name) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"Ask-Pw-123456\"}"));
		return login(email, "Ask-Pw-123456");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

package com.abhishekraj.BookMySeat.film;

import com.abhishekraj.BookMySeat.TestcontainersConfig;
import com.abhishekraj.BookMySeat.entity.Role;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Following films over HTTP. Same configuration as BookingApiTest (shared Spring context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class FollowApiTest {

	@Autowired MockMvc mvc;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired JdbcTemplate jdbc;

	String admin;
	String user;
	long venueId;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		user = registerAndLogin("follower@example.com", "Farah");
		venueId = ((Number) JsonPath.read(adminCall(post("/api/venues"), "{\"name\":\"Follow Hall\",\"city\":\"Pune\"}")
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("delete from email_outbox");
		showRepository.deleteAll();
		jdbc.update("delete from films f where not exists (select 1 from shows s where s.film_id = f.id)");
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
	}

	@Test
	void followAndStop_areIdempotent_andTheListSaysWhatIsComing() throws Exception {
		long withShows = film("Followed With Shows");
		long without = film("Followed Without Shows");
		show(withShows, 2);
		show(withShows, 3);

		follow(user, withShows).andExpect(status().isNoContent());
		follow(user, withShows).andExpect(status().isNoContent()); // twice is once
		follow(user, without).andExpect(status().isNoContent());
		mvc.perform(get("/api/films/following").header("Authorization", "Bearer " + user))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].title", contains("Followed Without Shows", "Followed With Shows")))
				.andExpect(jsonPath("$[1].upcomingShows").value(2))
				.andExpect(jsonPath("$[0].upcomingShows").value(0));

		mvc.perform(delete("/api/films/" + withShows + "/follow").header("Authorization", "Bearer " + user))
				.andExpect(status().isNoContent());
		mvc.perform(delete("/api/films/" + withShows + "/follow").header("Authorization", "Bearer " + user))
				.andExpect(status().isNoContent()); // stopping again is fine
		mvc.perform(get("/api/films/following").header("Authorization", "Bearer " + user))
				.andExpect(jsonPath("$[*].title", contains("Followed Without Shows")));
	}

	@Test
	void loginIsNeeded_andTheFilmMustExist() throws Exception {
		long filmId = film("Follow Needs Login");
		mvc.perform(put("/api/films/" + filmId + "/follow")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/films/following")).andExpect(status().isUnauthorized());
		follow(user, 999999).andExpect(status().isNotFound());
		// The film's own page stays public
		mvc.perform(get("/api/films/" + filmId)).andExpect(status().isOk());
	}

	@Test
	void followingFromTwoTabsAtOnce_isOneFollow() throws Exception {
		long filmId = film("Follow Race");
		ExecutorService pool = Executors.newFixedThreadPool(5);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			results.add(pool.submit(() -> {
				start.await();
				return follow(user, filmId).andReturn().getResponse().getStatus();
			}));
		}
		start.countDown();
		for (Future<Integer> result : results) {
			assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(204);
		}
		pool.shutdown();
		assertThat(jdbc.queryForObject("select count(*) from film_follows where film_id = ?", Integer.class, filmId)).isEqualTo(1);
	}

	@Test
	void newShowtimes_areAnnouncedToFollowers_once_inOneEmail_afterTheGracePeriod() throws Exception {
		long followed = film("Announced Film");
		long unfollowed = film("Nobody Follows");
		String second = registerAndLogin("follower-two@example.com", "Gita");
		registerAndLogin("not-following@example.com", "Hari");
		follow(user, followed);
		follow(second, followed);
		jdbc.update("delete from email_outbox");

		long first = show(followed, 3);
		long sooner = show(followed, 2);
		long cancelled = show(followed, 4);
		adminCall(post("/api/shows/" + cancelled + "/cancel"), "").andExpect(status().isOk());
		long other = show(unfollowed, 2);

		// Just created: an admin may still fix them, so nobody is told yet
		assertThat(announcements.announceDue()).isZero();
		createdAgo("20 minutes", first, sooner, cancelled, other);

		assertThat(announcements.announceDue()).isEqualTo(2); // one email to each follower
		List<String> recipients = jdbc.queryForList("select recipient from email_outbox where kind = 'NEW_SHOWTIMES'", String.class);
		assertThat(recipients).containsExactlyInAnyOrder("follower-two@example.com", "follower@example.com");
		String body = jdbc.queryForObject("select body_text from email_outbox where recipient = 'follower@example.com'", String.class);
		String subject = jdbc.queryForObject("select subject from email_outbox where recipient = 'follower@example.com'", String.class);
		assertThat(subject).isEqualTo("New showtimes for Announced Film");
		assertThat(body).contains("Hi Farah", "Announced Film has 2 new showtimes:", "Follow Hall, Pune", "from ₹200.00",
				"/#/film/" + followed, "To stop, open the film's page and press Following");
		assertThat(body.indexOf(when(sooner))).isEqualTo(body.indexOf("- ") + 2); // the soonest is listed first
		// Every show was looked at, the cancelled one and the unfollowed film's too, and nothing is told twice
		assertThat(jdbc.queryForObject("select count(*) from shows where followers_notified_at is null and id in (?, ?, ?, ?)",
				Integer.class, first, sooner, cancelled, other)).isZero();
		assertThat(announcements.announceDue()).isZero();

		// A later showtime: just that one
		long later = show(followed, 9);
		createdAgo("20 minutes", later);
		jdbc.update("delete from email_outbox");
		assertThat(announcements.announceDue()).isEqualTo(2);
		assertThat(jdbc.queryForObject("select body_text from email_outbox where recipient = 'follower@example.com'", String.class))
				.contains("Announced Film has a new showtime:");
	}

	@Test
	void showsThatExistedBeforeV25_areNotNews() throws Exception {
		long filmId = film("Old News");
		follow(user, filmId);
		long old = show(filmId, 2);
		// As V25 left existing shows: already told
		jdbc.update("update shows set followers_notified_at = now(), created_at = now() - interval '1 day' where id = ?", old);
		jdbc.update("delete from email_outbox");
		assertThat(announcements.announceDue()).isZero();
	}

	@Autowired com.abhishekraj.BookMySeat.service.ShowAnnouncements announcements;

	void createdAgo(String interval, long... showIds) {
		for (long id : showIds) {
			jdbc.update("update shows set created_at = now() - interval '" + interval + "' where id = ?", id);
		}
	}

	/** The start time as the email writes it ("Fri 18 Dec 2027, 7:00 pm"), to find it in the text. */
	String when(long showId) {
		Instant start = jdbc.queryForObject("select start_time from shows where id = ?", java.sql.Timestamp.class, showId).toInstant();
		return java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", java.util.Locale.ENGLISH)
				.format(start.atZone(java.time.ZoneId.of("Asia/Kolkata"))).replace("AM", "am").replace("PM", "pm");
	}

	// ---- helpers ----

	ResultActions follow(String token, long filmId) throws Exception {
		return mvc.perform(put("/api/films/" + filmId + "/follow").header("Authorization", "Bearer " + token));
	}

	long film(String title) throws Exception {
		return ((Number) JsonPath.read(adminCall(post("/api/admin/films"), "{\"title\":\"" + title + "\",\"runtimeMinutes\":110}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	long show(long filmId, int days) throws Exception {
		String start = Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
		return ((Number) JsonPath.read(adminCall(post("/api/shows"), "{\"filmId\":" + filmId + ",\"startTime\":\"" + start
				+ "\",\"price\":200.00,\"venueId\":" + venueId + "}").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	ResultActions adminCall(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	String registerAndLogin(String email, String name) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"Follow-Pw-12345\"}"));
		return login(email, "Follow-Pw-12345");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

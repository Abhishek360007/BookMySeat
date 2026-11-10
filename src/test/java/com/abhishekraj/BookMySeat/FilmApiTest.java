package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Role;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Films over HTTP: creating and editing them, shows joining them, "now showing", a film's page, reviews and
// ratings across its screenings, and its runtime in the calendar event and the review emails.
// Same configuration as BookingApiTest (shared Spring context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class FilmApiTest {

	@Autowired MockMvc mvc;
	@Autowired ReviewRequests reviewRequests;
	@Autowired BookingRepository bookingRepository;
	@Autowired ReviewRepository reviewRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired JdbcTemplate jdbc;

	String admin;
	long pune;
	long mumbai;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		// Cities only this test uses: other tests' shows never show up in its searches
		pune = venue("Film Hall Pune", "Filmpur");
		mumbai = venue("Film Hall Mumbai", "Reelabad");
	}

	@AfterEach
	void cleanUp() {
		reviewRepository.deleteAll();
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		jdbc.update("delete from films f where not exists (select 1 from shows s where s.film_id = f.id)");
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
		redis.delete(redis.keys("cache:*"));
		jdbc.update("delete from email_outbox");
	}

	@Test
	void adminsCreateAndEditFilms_withUniqueTitles() throws Exception {
		long id = film("Arrival", 116, "English", "UA");
		adminCall(post("/api/admin/films"), filmJson("ARRIVAL", 100, null, null)).andExpect(status().isConflict());
		adminCall(post("/api/admin/films"), filmJson("Zero", 0, null, null)).andExpect(status().isBadRequest());
		adminCall(post("/api/admin/films"), filmJson("Rated X", 90, null, "X")).andExpect(status().isBadRequest());
		adminCall(post("/api/admin/films"), "{\"runtimeMinutes\":90}").andExpect(status().isBadRequest());
		String customer = registerAndLogin("film-customer@example.com", "Cara");
		mvc.perform(post("/api/admin/films").header("Authorization", "Bearer " + customer)
				.contentType(MediaType.APPLICATION_JSON).content(filmJson("Mine", 90, null, null))).andExpect(status().isForbidden());

		// A show joins the film by id, or by its title in any case; its title becomes the film's
		long byId = show(id, null, null, pune, 1);
		long byTitle = show(null, "arrival", null, pune, 2);
		mvc.perform(get("/api/shows/" + byTitle)).andExpect(jsonPath("$.title").value("Arrival"))
				.andExpect(jsonPath("$.filmId").value(id)).andExpect(jsonPath("$.runtimeMinutes").value(116));
		// A new title makes a new film, with the runtime given or 180 minutes
		long newFilmShow = show(null, "Brand New", 95, pune, 1);
		mvc.perform(get("/api/shows/" + newFilmShow)).andExpect(jsonPath("$.runtimeMinutes").value(95));
		adminCall(post("/api/shows"), "{\"startTime\":\"" + in(1) + "\",\"price\":200,\"venueId\":" + pune + "}")
				.andExpect(status().isBadRequest());
		adminCall(post("/api/shows"), "{\"filmId\":999999,\"startTime\":\"" + in(1) + "\",\"price\":200,\"venueId\":" + pune + "}")
				.andExpect(status().isNotFound());

		mvc.perform(get("/api/shows/" + byId)).andExpect(jsonPath("$.title").value("Arrival")); // cached now
		adminCall(put("/api/admin/films/" + id), filmJson("Arrival (2016)", 118, "English", "U"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.shows").value(2));
		// Renamed: its shows say so, cached or not; the runtime too
		mvc.perform(get("/api/shows/" + byId)).andExpect(jsonPath("$.title").value("Arrival (2016)"))
				.andExpect(jsonPath("$.runtimeMinutes").value(118));
		adminCall(put("/api/admin/films/" + id), filmJson("brand new", 90, null, null)).andExpect(status().isConflict());

		adminCall(get("/api/admin/films?q=arriv"), "").andExpect(jsonPath("$.content[*].title", contains("Arrival (2016)")))
				.andExpect(jsonPath("$.content[0].shows").value(2));
	}

	@Test
	void showsCreatedAtOnce_withANewTitle_makeOneFilm() throws Exception {
		int people = 6;
		ExecutorService pool = Executors.newFixedThreadPool(people);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<MvcResult>> results = new ArrayList<>();
		for (int i = 0; i < people; i++) {
			int day = i + 1;
			results.add(pool.submit(() -> {
				start.await();
				return adminCall(post("/api/shows"), "{\"title\":\"Race Film\",\"startTime\":\"" + in(day)
						+ "\",\"price\":200,\"venueId\":" + pune + "}").andReturn();
			}));
		}
		start.countDown();
		for (Future<MvcResult> result : results) {
			assertThat(result.get(60, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
		}
		pool.shutdown();
		assertThat(jdbc.queryForObject("select count(*) from films where lower(title) = 'race film'", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(distinct film_id) from shows where title = 'Race Film'", Integer.class)).isEqualTo(1);
	}

	@Test
	void nowShowing_listsFilmsWithUpcomingScreenings_soonestFirst() throws Exception {
		long later = film("Later 100%", 120, null, null);
		long sooner = film("Sooner", 90, null, null);
		long gone = film("Gone", 90, null, null);
		film("Never Shown", 90, null, null);
		for (int day = 1; day <= 4; day++) {
			show(later, null, null, pune, day + 1);
		}
		long mumbaiShow = show(later, null, null, mumbai, 9);
		jdbc.update("update shows set price = 150 where id = ?", mumbaiShow);
		show(sooner, null, null, pune, 1);
		long cancelled = show(gone, null, null, pune, 1);
		adminCall(post("/api/shows/" + cancelled + "/cancel"), "").andExpect(status().isOk());
		long past = show(gone, null, null, pune, 1);
		jdbc.update("update shows set start_time = now() - interval '1 day' where id = ?", past);

		mvc.perform(get("/api/films").param("city", "Filmpur"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].title", contains("Sooner", "Later 100%")))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[1].upcomingShows").value(4))
				.andExpect(jsonPath("$.content[1].nextShows.length()").value(3))
				.andExpect(jsonPath("$.content[1].runtimeMinutes").value(120))
				.andExpect(jsonPath("$.content[1].ratingAverage", nullValue()));
		// Every city: all 5 screenings, the cheapest seat is in Reelabad
		mvc.perform(get("/api/films").param("q", "Later 100%"))
				.andExpect(jsonPath("$.content[*].title", contains("Later 100%")))
				.andExpect(jsonPath("$.content[0].upcomingShows").value(5))
				.andExpect(jsonPath("$.content[0].fromPrice").value(150.0));
		mvc.perform(get("/api/films").param("city", "reelabad"))
				.andExpect(jsonPath("$.content[*].title", contains("Later 100%")))
				.andExpect(jsonPath("$.content[0].upcomingShows").value(1))
				.andExpect(jsonPath("$.content[0].nextShows[0].city").value("Reelabad"));
		mvc.perform(get("/api/films").param("q", "0%").param("city", "Filmpur"))
				.andExpect(jsonPath("$.content[*].title", contains("Later 100%")));
		mvc.perform(get("/api/films").param("q", "r%").param("city", "Filmpur"))
				.andExpect(jsonPath("$.totalElements").value(0)); // "%" is taken as text, not a wildcard
		mvc.perform(get("/api/films").param("city", "Filmpur").param("size", "1").param("page", "1"))
				.andExpect(jsonPath("$.content[*].title", contains("Later 100%")))
				.andExpect(jsonPath("$.totalPages").value(2));
		mvc.perform(get("/api/films?size=51")).andExpect(status().isBadRequest());

		mvc.perform(get("/api/films/" + later)).andExpect(jsonPath("$.shows.length()").value(5))
				.andExpect(jsonPath("$.shows[0].venueName").value("Film Hall Pune"));
		mvc.perform(get("/api/films/" + gone)).andExpect(jsonPath("$.shows.length()").value(0));
		mvc.perform(get("/api/films/999999")).andExpect(status().isNotFound());
	}

	@Test
	void reviews_countForTheFilm_acrossItsScreenings_oncePerPerson() throws Exception {
		long filmId = film("Two Screenings", 100, null, null);
		long first = showWithSeats(filmId, 1);
		long second = showWithSeats(filmId, 2);
		String asha = registerAndLogin("film-asha@example.com", "Asha");
		String ravi = registerAndLogin("film-ravi@example.com", "Ravi");
		book(asha, first, 0);
		book(asha, second, 0); // two screenings, one review
		book(ravi, second, 1);
		startedAgo(first, "2 hours");

		// Ravi's screening hasn't started; Asha's first one has
		review(ravi, "/api/films/" + filmId, 2).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_STARTED"));
		review(asha, "/api/films/" + filmId, 5).andExpect(status().isCreated());
		review(asha, "/api/shows/" + second, 4).andExpect(status().isOk()); // through the other screening: an edit
		startedAgo(second, "1 hour");
		review(ravi, "/api/shows/" + second, 2).andExpect(status().isCreated());

		assertThat(reviewRepository.count()).isEqualTo(2);
		mvc.perform(get("/api/films/" + filmId)).andExpect(jsonPath("$.ratingAverage").value(3.0))
				.andExpect(jsonPath("$.ratingCount").value(2));
		// Every screening shows the film's rating and reviews
		mvc.perform(get("/api/shows/" + first)).andExpect(jsonPath("$.ratingAverage").value(3.0));
		mvc.perform(get("/api/shows/" + first + "/reviews")).andExpect(jsonPath("$.totalElements").value(2));
		mvc.perform(get("/api/films/" + filmId + "/reviews?sort=highest")).andExpect(jsonPath("$.content[*].rating", contains(4, 2)));
		mvc.perform(get("/api/reviews/me").header("Authorization", "Bearer " + asha))
				.andExpect(jsonPath("$[0].filmId").value(filmId)).andExpect(jsonPath("$[0].filmTitle").value("Two Screenings"));

		// A show with bookings can't move to another film (its customers' reviews count for this one)
		long other = film("Other Film", 90, null, null);
		adminCall(put("/api/shows/" + second), "{\"filmId\":" + other + ",\"startTime\":\"" + in(3)
				+ "\",\"price\":200,\"venueId\":" + pune + "}").andExpect(status().isConflict());
	}

	@Test
	void theRuntime_endsTheCalendarEvent_andTimesTheReviewEmail() throws Exception {
		long shortFilm = film("Short Film", 60, null, null);
		long longFilm = film("Long Film", 240, null, null);
		long shortShow = showWithSeats(shortFilm, 1);
		long longShow = showWithSeats(longFilm, 1);
		String asha = registerAndLogin("film-runtime@example.com", "Asha");
		int booking = book(asha, shortShow, 0);
		book(asha, longShow, 0);

		String ics = new String(mvc.perform(get("/api/bookings/" + booking + "/ticket.ics").header("Authorization", "Bearer " + asha))
				.andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
		Instant start = Instant.parse(JsonPath.read(mvc.perform(get("/api/shows/" + shortShow)).andReturn().getResponse()
				.getContentAsString(), "$.startTime"));
		DateTimeFormatter utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
		assertThat(ics).contains("DTEND:" + utc.format(start.plus(60, ChronoUnit.MINUTES)) + "\r\n");

		// Both started 2 hours ago: the 1-hour film is over (plus 30 minutes), the 4-hour one isn't
		startedAgo(shortShow, "2 hours");
		startedAgo(longShow, "2 hours");
		assertThat(reviewRequests.sendDue()).isEqualTo(1);
		assertThat(jdbc.queryForList("select subject from email_outbox where kind = 'REVIEW_REQUEST'", String.class))
				.containsExactly("How was Short Film?");
	}

	// ---- helpers ----

	ResultActions review(String token, String base, int rating) throws Exception {
		return mvc.perform(put(base + "/reviews/me").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"rating\":" + rating + "}"));
	}

	void startedAgo(long showId, String interval) {
		jdbc.update("update shows set start_time = now() - interval '" + interval + "' where id = ?", showId);
	}

	long film(String title, int runtime, String language, String certificate) throws Exception {
		return ((Number) JsonPath.read(adminCall(post("/api/admin/films"), filmJson(title, runtime, language, certificate))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	static String filmJson(String title, int runtime, String language, String certificate) {
		return "{\"title\":\"" + title + "\",\"runtimeMinutes\":" + runtime
				+ (language == null ? "" : ",\"language\":\"" + language + "\"")
				+ (certificate == null ? "" : ",\"certificate\":\"" + certificate + "\"") + "}";
	}

	long show(Long filmId, String title, Integer runtime, long venueId, int days) throws Exception {
		String body = "{" + (filmId == null ? "" : "\"filmId\":" + filmId + ",")
				+ (title == null ? "" : "\"title\":\"" + title + "\",")
				+ (runtime == null ? "" : "\"runtimeMinutes\":" + runtime + ",")
				+ "\"startTime\":\"" + in(days) + "\",\"price\":200.00,\"venueId\":" + venueId + "}";
		return ((Number) JsonPath.read(adminCall(post("/api/shows"), body).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	long showWithSeats(long filmId, int days) throws Exception {
		long id = show(filmId, null, null, pune, days);
		adminCall(post("/api/shows/" + id + "/seats"), "{\"rows\":1,\"seatsPerRow\":4}").andExpect(status().isCreated());
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

	static String in(int days) {
		return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
	}

	long venue(String name, String city) throws Exception {
		return ((Number) JsonPath.read(adminCall(post("/api/venues"), "{\"name\":\"" + name + "\",\"city\":\"" + city + "\"}")
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	ResultActions adminCall(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	String registerAndLogin(String email, String name) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"Film-Pw-123456\"}"));
		return login(email, "Film-Pw-123456");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

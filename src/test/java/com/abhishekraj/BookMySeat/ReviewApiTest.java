package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.ReviewRepository;
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
import org.springframework.test.web.servlet.MvcResult;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Reviews over HTTP: who may write one, the average kept on the show (and in its cache), listing, moderation,
// and people saving at the same moment. Same configuration as BookingApiTest (shared Spring context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ReviewApiTest {

	@Autowired MockMvc mvc;
	@Autowired BookingRepository bookingRepository;
	@Autowired ReviewRepository reviewRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired JdbcTemplate jdbc;

	String admin;
	long venueId;
	long showId;
	List<Integer> seats;
	int nextSeat;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		venueId = ((Number) JsonPath.read(adminCall(post("/api/venues"), "{\"name\":\"Review Venue\",\"city\":\"Pune\"}")
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		showId = createShow("Review Show");
		adminCall(post("/api/shows/" + showId + "/seats"), "{\"rows\":2,\"seatsPerRow\":10}").andExpect(status().isCreated());
		seats = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats")).andReturn().getResponse()
				.getContentAsString(), "$[*].id");
		nextSeat = 0;
	}

	@AfterEach
	void cleanUp() {
		reviewRepository.deleteAll();
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		// Films outlive their shows; these tests count ratings, so start each one from fresh films
		jdbc.update("delete from films f where not exists (select 1 from shows s where s.film_id = f.id)");
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
		redis.delete(redis.keys("cache:*"));
	}

	@Test
	void onlyPeopleWhoWent_canReview() throws Exception {
		String booked = bookedCustomer("rv-booked@example.com", "Asha Kumar");
		String other = registerAndLogin("rv-other@example.com", "Ravi");

		review(booked, 5, "Loved it").andExpect(status().isConflict())
				.andExpect(jsonPath("$.reason").value("NOT_STARTED"))
				.andExpect(jsonPath("$.detail").value("You can review this film once your screening has started"));
		showStarted();
		review(other, 5, null).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NO_BOOKING"));
		mvc.perform(put("/api/shows/" + showId + "/reviews/me").contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\":5}")).andExpect(status().isUnauthorized());
		mvc.perform(put("/api/shows/999999/reviews/me").header("Authorization", "Bearer " + booked)
				.contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5}")).andExpect(status().isNotFound());

		// A cancelled booking doesn't count either (cancelled while the show was still ahead)
		String cancelled = registerAndLogin("rv-cancelled@example.com", "Cara");
		jdbc.update("update shows set start_time = now() + interval '1 day' where id = ?", showId);
		int bookingId = book(cancelled);
		mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", "Bearer " + cancelled))
				.andExpect(status().isOk());
		showStarted();
		review(cancelled, 4, null).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NO_BOOKING"));

		review(booked, 5, "Loved it").andExpect(status().isCreated());
	}

	@Test
	void writeEditAndDelete_keepTheShowsAverage_andItsCacheRight() throws Exception {
		String asha = bookedCustomer("rv-asha@example.com", "Asha Kumar");
		String ravi = bookedCustomer("rv-ravi@example.com", "Ravi");
		showStarted();
		show().andExpect(jsonPath("$.ratingCount").value(0)).andExpect(jsonPath("$.ratingAverage", nullValue())); // cached now

		review(asha, 5, "  Great sound.\r\n\r\n\r\n\r\n\r\nGreat seats!  ").andExpect(status().isCreated())
				.andExpect(jsonPath("$.rating").value(5))
				.andExpect(jsonPath("$.text").value("Great sound.\n\n\nGreat seats!"))
				.andExpect(jsonPath("$.hidden").value(false));
		show().andExpect(jsonPath("$.ratingCount").value(1)).andExpect(jsonPath("$.ratingAverage").value(5.0));

		review(ravi, 2, "   ").andExpect(status().isCreated()).andExpect(jsonPath("$.text", nullValue()));
		show().andExpect(jsonPath("$.ratingCount").value(2)).andExpect(jsonPath("$.ratingAverage").value(3.5));

		// Editing: same review (200), the average follows
		review(ravi, 3, "Better on second thought").andExpect(status().isOk());
		show().andExpect(jsonPath("$.ratingCount").value(2)).andExpect(jsonPath("$.ratingAverage").value(4.0));
		assertThat(reviewRepository.count()).isEqualTo(2);
		// Search results carry it too
		mvc.perform(get("/api/shows/search").param("q", "Review Show")
						.param("from", Instant.now().minus(1, ChronoUnit.DAYS).toString()))
				.andExpect(jsonPath("$.content[0].ratingAverage").value(4.0));

		mvc.perform(delete("/api/shows/" + showId + "/reviews/me").header("Authorization", "Bearer " + asha))
				.andExpect(status().isNoContent());
		show().andExpect(jsonPath("$.ratingCount").value(1)).andExpect(jsonPath("$.ratingAverage").value(3.0));
		mvc.perform(delete("/api/shows/" + showId + "/reviews/me").header("Authorization", "Bearer " + asha))
				.andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select rating_sum from films where id = (select film_id from shows where id = ?)", Integer.class, showId)).isEqualTo(3);
	}

	@Test
	void badInput_isA400() throws Exception {
		String asha = bookedCustomer("rv-input@example.com", "Asha");
		showStarted();
		review(asha, 0, null).andExpect(status().isBadRequest());
		review(asha, 6, null).andExpect(status().isBadRequest());
		mvc.perform(put("/api/shows/" + showId + "/reviews/me").header("Authorization", "Bearer " + asha)
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
		review(asha, 4, "x".repeat(1001)).andExpect(status().isBadRequest());
		review(asha, 4, "x".repeat(1000)).andExpect(status().isCreated());
		mvc.perform(get("/api/shows/" + showId + "/reviews?sort=worst")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/shows/" + showId + "/reviews?size=51")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/shows/999999/reviews")).andExpect(status().isNotFound());
	}

	@Test
	void listing_isPublic_newestOrHighestFirst_withShortNames() throws Exception {
		String asha = bookedCustomer("rv-l1@example.com", "Asha Kumar");
		String ravi = bookedCustomer("rv-l2@example.com", "Ravi");
		String meera = bookedCustomer("rv-l3@example.com", "meera  s  iyer");
		showStarted();
		review(asha, 3, "Fine").andExpect(status().isCreated());
		review(ravi, 5, "Superb").andExpect(status().isCreated());
		review(meera, 4, null).andExpect(status().isCreated());

		mvc.perform(get("/api/shows/" + showId + "/reviews"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].rating", contains(4, 5, 3)))
				.andExpect(jsonPath("$.content[*].author", contains("meera I.", "Ravi", "Asha K.")))
				.andExpect(jsonPath("$.content[0].authorEmail").doesNotExist())
				.andExpect(jsonPath("$.totalElements").value(3));
		mvc.perform(get("/api/shows/" + showId + "/reviews?sort=highest&size=2"))
				.andExpect(jsonPath("$.content[*].rating", contains(5, 4)))
				.andExpect(jsonPath("$.totalPages").value(2));

		mvc.perform(get("/api/reviews/me").header("Authorization", "Bearer " + ravi))
				.andExpect(jsonPath("$[*].showId", contains((int) showId)))
				.andExpect(jsonPath("$[0].filmTitle").value("Review Show"))
				.andExpect(jsonPath("$[0].text").value("Superb"));
	}

	@Test
	void hiddenReviews_areNotListedOrCounted_andOnlyAdminsModerate() throws Exception {
		String asha = bookedCustomer("rv-h1@example.com", "Asha");
		String ravi = bookedCustomer("rv-h2@example.com", "Ravi");
		showStarted();
		review(asha, 5, "Great").andExpect(status().isCreated());
		int spam = JsonPath.read(review(ravi, 1, "Buy cheap tickets at ...").andReturn().getResponse().getContentAsString(), "$.id");
		show().andExpect(jsonPath("$.ratingAverage").value(3.0));

		mvc.perform(post("/api/admin/reviews/" + spam + "/hide").header("Authorization", "Bearer " + ravi))
				.andExpect(status().isForbidden());
		adminCall(post("/api/admin/reviews/" + spam + "/hide"), "").andExpect(status().isOk())
				.andExpect(jsonPath("$.hidden").value(true))
				.andExpect(jsonPath("$.authorEmail").value("rv-h2@example.com"));
		adminCall(post("/api/admin/reviews/" + spam + "/hide"), "").andExpect(status().isOk()); // twice: no change
		show().andExpect(jsonPath("$.ratingCount").value(1)).andExpect(jsonPath("$.ratingAverage").value(5.0));
		mvc.perform(get("/api/shows/" + showId + "/reviews")).andExpect(jsonPath("$.content[*].author", contains("Asha")));
		adminCall(get("/api/admin/reviews?hidden=true"), "").andExpect(jsonPath("$.content[*].id", contains(spam)));

		// Editing a hidden review keeps it hidden and out of the average; its author can still see it
		review(ravi, 2, "Still spam").andExpect(status().isOk()).andExpect(jsonPath("$.hidden").value(true));
		show().andExpect(jsonPath("$.ratingAverage").value(5.0));

		adminCall(post("/api/admin/reviews/" + spam + "/unhide"), "").andExpect(jsonPath("$.hidden").value(false));
		show().andExpect(jsonPath("$.ratingCount").value(2)).andExpect(jsonPath("$.ratingAverage").value(3.5));
		adminCall(post("/api/admin/reviews/999999/hide"), "").andExpect(status().isNotFound());

		// Deleting a hidden review doesn't take it out of the average twice
		adminCall(post("/api/admin/reviews/" + spam + "/hide"), "").andExpect(status().isOk());
		mvc.perform(delete("/api/shows/" + showId + "/reviews/me").header("Authorization", "Bearer " + ravi))
				.andExpect(status().isNoContent());
		show().andExpect(jsonPath("$.ratingCount").value(1)).andExpect(jsonPath("$.ratingAverage").value(5.0));
	}

	@Test
	void savingAtTheSameMoment_givesOneReviewPerPerson_andAnExactAverage() throws Exception {
		String me = bookedCustomer("rv-race-me@example.com", "Me");
		List<String> others = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			others.add(bookedCustomer("rv-race-" + i + "@example.com", "Racer " + i));
		}
		showStarted();

		// One person, 6 saves at once (a double click, several tabs): one review, created once, the others edit it
		List<Integer> statuses = race(6, i -> review(me, (i % 5) + 1, "Take " + i).andReturn());
		assertThat(statuses).containsOnly(201, 200);
		assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
		assertThat(reviewRepository.count()).isEqualTo(1);

		// 8 different people at once: every one of them counts
		race(8, i -> review(others.get(i), (i % 5) + 1, null).andReturn());
		int myRating = JsonPath.read(mvc.perform(get("/api/reviews/me").header("Authorization", "Bearer " + me))
				.andReturn().getResponse().getContentAsString(), "$[0].rating");
		int expectedSum = myRating + (1 + 2 + 3 + 4 + 5 + 1 + 2 + 3);
		assertThat(jdbc.queryForObject("select rating_count from films where id = (select film_id from shows where id = ?)", Integer.class, showId)).isEqualTo(9);
		assertThat(jdbc.queryForObject("select rating_sum from films where id = (select film_id from shows where id = ?)", Integer.class, showId)).isEqualTo(expectedSum);
		assertThat(jdbc.queryForObject("select sum(rating) from reviews where show_id = ?", Integer.class, showId))
				.isEqualTo(expectedSum);
	}

	interface Call {
		MvcResult run(int i) throws Exception;
	}

	List<Integer> race(int n, Call call) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<MvcResult>> futures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int index = i;
			futures.add(pool.submit(() -> {
				start.await();
				return call.run(index);
			}));
		}
		start.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<MvcResult> future : futures) {
			statuses.add(future.get(60, TimeUnit.SECONDS).getResponse().getStatus());
		}
		pool.shutdown();
		return statuses;
	}

	ResultActions review(String token, int rating, String text) throws Exception {
		String body = "{\"rating\":" + rating + (text == null ? "" : ",\"text\":" + json(text)) + "}";
		return mvc.perform(put("/api/shows/" + showId + "/reviews/me").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	static String json(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\"";
	}

	ResultActions show() throws Exception {
		return mvc.perform(get("/api/shows/" + showId)).andExpect(status().isOk());
	}

	void showStarted() {
		jdbc.update("update shows set start_time = now() - interval '4 hours' where id = ?", showId);
	}

	String bookedCustomer(String email, String name) throws Exception {
		String token = registerAndLogin(email, name);
		book(token);
		return token;
	}

	int book(String token) throws Exception {
		String body = "{\"showId\":" + showId + ",\"seatIds\":[" + seats.get(nextSeat++) + "]}";
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		return JsonPath.read(mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	long createShow(String title) throws Exception {
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		return ((Number) JsonPath.read(adminCall(post("/api/shows"), "{\"title\":\"" + title + "\",\"startTime\":\"" + start
				+ "\",\"price\":200.00,\"venueId\":" + venueId + "}").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	ResultActions adminCall(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	String registerAndLogin(String email, String name) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"Review-Pw-12345\"}"));
		return login(email, "Review-Pw-12345");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

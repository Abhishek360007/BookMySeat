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

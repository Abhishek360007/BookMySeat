package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The admin dashboard API: per-show sales in a fixed number of queries, and headline numbers
@SpringBootTest(properties = {
		"app.admin.email=dash-test-admin@example.com",
		"app.admin.password=Dash-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000",
		"spring.jpa.properties.hibernate.generate_statistics=true",
		// Statistics are global: keep background email sending from adding its queries to the count
		"email.outbox.send-immediately=false",
		"email.outbox.initial-delay=PT1H",
		"logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AdminDashboardApiTest {

	@Autowired MockMvc mvc;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired JdbcTemplate jdbc;
	@Autowired EntityManagerFactory entityManagerFactory;

	String admin;
	String user;
	long dune;
	long batman;
	long empty;
	long past;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("dash-test-admin@example.com", "Dash-Test-Admin-Pw-1");
		long pune = venue("Dash Pune", "Pune");
		long delhi = venue("Dash Delhi", "Delhi");
		dune = show("Dune: Part Three", 1, "250.00", pune, 2, 5);    // 10 seats
		batman = show("The Batman II", 2, "300.00", delhi, 1, 4);    // 4 seats
		empty = show("Empty Show", 3, "100.00", delhi, 0, 0);        // no seats yet
		past = show("Old Classic", 4, "200.00", pune, 1, 3);
		user = registerAndLogin("dash-user@example.com");

		book(dune, 0, 1, 2);   // 3 seats x 250 = 750
		book(batman, 0);       // 1 seat x 300 = 300
		book(past, 0);         // 200, then the show "happens"
		int cancelled = book(dune, 5);
		mvc.perform(post("/api/bookings/" + cancelled + "/cancel").header("Authorization", "Bearer " + user))
				.andExpect(status().isOk());
		jdbc.update("update shows set start_time = now() - interval '1 day' where id = ?", past);
	}

	@AfterEach
	void cleanUp() {
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
	}

	@Test
	void upcomingShows_withTheirSales_soonestFirst() throws Exception {
		shows("").andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.content[*].title", contains("Dune: Part Three", "The Batman II", "Empty Show")))
				.andExpect(jsonPath("$.content[0].venueName").value("Dash Pune"))
				.andExpect(jsonPath("$.content[0].city").value("Pune"))
				.andExpect(jsonPath("$.content[0].totalSeats").value(10))
				.andExpect(jsonPath("$.content[0].bookedSeats").value(3)) // the cancelled booking freed its seat
				.andExpect(jsonPath("$.content[0].confirmedBookings").value(1))
				.andExpect(jsonPath("$.content[0].revenue").value(750.00))
				.andExpect(jsonPath("$.content[1].bookedSeats").value(1))
				.andExpect(jsonPath("$.content[1].revenue").value(300.00))
				.andExpect(jsonPath("$.content[2].totalSeats").value(0))
				.andExpect(jsonPath("$.content[2].confirmedBookings").value(0))
				.andExpect(jsonPath("$.content[2].revenue").value(0.00));
	}

	@Test
	void pastAllAndTitleFilters() throws Exception {
		shows("?when=PAST").andExpect(jsonPath("$.content[*].title", contains("Old Classic")))
				.andExpect(jsonPath("$.content[0].revenue").value(200.00));
		shows("?when=ALL&sort=startTime,desc").andExpect(jsonPath("$.totalElements").value(4))
				.andExpect(jsonPath("$.content[0].title").value("Empty Show"));
		shows("?when=ALL&q=batman").andExpect(jsonPath("$.content[*].title", contains("The Batman II")));
		shows("?when=SOMETIME").andExpect(status().isBadRequest());
	}

	@Test
	void overview_countsConfirmedSalesOnly() throws Exception {
		mvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.upcomingShows").value(3))
				.andExpect(jsonPath("$.confirmedBookings").value(3))
				.andExpect(jsonPath("$.revenue").value(1250.00))
				.andExpect(jsonPath("$.bookingsLast7Days").value(3))
				.andExpect(jsonPath("$.revenueLast7Days").value(1250.00))
				.andExpect(jsonPath("$.upcomingSeats").value(14))
				.andExpect(jsonPath("$.upcomingSeatsBooked").value(4));
	}

	@Test
	void pageSize_doesNotChangeTheNumberOfQueries() throws Exception {
		long small = queriesFor("?when=ALL&size=1");
		long large = queriesFor("?when=ALL&size=4");
		assertThat(small).isEqualTo(large).isLessThanOrEqualTo(4); // shows, count, seats, bookings
	}

	@Test
	void onlyAdmins() throws Exception {
		mvc.perform(get("/api/admin/shows").header("Authorization", "Bearer " + user)).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + user)).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/overview")).andExpect(status().isUnauthorized());
	}

	// ---- helpers ----

	long queriesFor(String query) throws Exception {
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		shows(query).andExpect(status().isOk());
		return statistics.getPrepareStatementCount();
	}

	ResultActions shows(String query) throws Exception {
		return mvc.perform(get("/api/admin/shows" + query).header("Authorization", "Bearer " + admin));
	}

	int book(long showId, int... seatIndexes) throws Exception {
		List<Integer> all = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats"))
				.andReturn().getResponse().getContentAsString(), "$[*].id");
		List<Integer> ids = java.util.Arrays.stream(seatIndexes).mapToObj(all::get).toList();
		String body = "{\"showId\":" + showId + ",\"seatIds\":" + ids + "}";
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + user)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		return JsonPath.read(mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + user)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	long venue(String name, String city) throws Exception {
		return ((Number) JsonPath.read(mvc.perform(post("/api/venues").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"city\":\"" + city + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	long show(String title, int daysAhead, String price, long venueId, int rows, int perRow) throws Exception {
		Instant start = Instant.now().plus(daysAhead, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		long id = ((Number) JsonPath.read(mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"" + title + "\",\"startTime\":\"" + start + "\",\"price\":" + price + ",\"venueId\":" + venueId + "}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		if (rows > 0) {
			mvc.perform(post("/api/shows/" + id + "/seats").header("Authorization", "Bearer " + admin)
							.contentType(MediaType.APPLICATION_JSON).content("{\"rows\":" + rows + ",\"seatsPerRow\":" + perRow + "}"))
					.andExpect(status().isCreated());
		}
		return id;
	}

	String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Dash Test\",\"email\":\"" + email + "\",\"password\":\"Dash-User-Pw-123\"}"));
		return login(email, "Dash-User-Pw-123");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}

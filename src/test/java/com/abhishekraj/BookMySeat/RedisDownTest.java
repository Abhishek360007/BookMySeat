package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.Venue;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Redis points at a port where nothing listens: the cache and the rate limiter must degrade, not fail requests.
// (This happened in production: the first Upstash connection after a cold start timed out and search returned 500.)
@SpringBootTest(properties = {
		"spring.data.redis.host=localhost",
		"spring.data.redis.port=1",
		"spring.data.redis.timeout=500ms"
})
@AutoConfigureMockMvc
@Import(RedisDownTest.PostgresOnly.class)
class RedisDownTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class PostgresOnly {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
		}
	}

	@Autowired MockMvc mvc;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;

	@Test
	void cachedReadsFallBackToTheDatabase() throws Exception {
		Venue venue = new Venue();
		venue.setName("No Redis Venue");
		venue.setCity("Offline");
		venue = venueRepository.save(venue);
		Show show = new Show();
		show.setTitle("Still Works");
		show.setVenue(venue);
		show.setStartTime(Instant.now().plus(1, ChronoUnit.DAYS));
		show.setPrice(new BigDecimal("250.00"));
		show = showRepository.save(show);

		mvc.perform(get("/api/shows/search?city=offline"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].title").value("Still Works"));
		mvc.perform(get("/api/shows/" + show.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Still Works"));
	}

	@Test
	void rateLimiterFailsOpen() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}"))
				.andExpect(status().isUnauthorized()); // not 429 and not 500
	}
}

package com.abhishekraj.BookMySeat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real Redis (Testcontainers) behind the per-IP limits, with low limits so the tests stay fast
@SpringBootTest(properties = {
		"rate-limit.login.max-requests=3",
		"rate-limit.login.window-seconds=60",
		"rate-limit.register.max-requests=2",
		"rate-limit.register.window-seconds=3600"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RateLimitTest {

	@Autowired MockMvc mvc;
	@Autowired StringRedisTemplate redis;

	@BeforeEach
	void resetCounters() {
		redis.delete(redis.keys("rate:*"));
	}

	@Test
	void fourthLoginInAMinute_is429WithRetryAfter() throws Exception {
		for (int i = 0; i < 3; i++) {
			login("10.0.0.1").andExpect(status().isUnauthorized()); // failed attempts count too
		}

		login("10.0.0.1")
				.andExpect(status().isTooManyRequests())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(429))
				.andExpect(jsonPath("$.detail", containsString("Too many login attempts")))
				.andExpect(header().exists("Retry-After"));
	}

	@Test
	void limitsArePerIpAddress() throws Exception {
		for (int i = 0; i < 3; i++) {
			login("10.0.0.1");
		}
		login("10.0.0.1").andExpect(status().isTooManyRequests());

		login("10.0.0.2").andExpect(status().isUnauthorized()); // a different client is unaffected
	}

	@Test
	void registrationHasItsOwnLimit() throws Exception {
		register("10.0.0.3", "rl-user1@example.com").andExpect(status().isCreated());
		register("10.0.0.3", "rl-user2@example.com").andExpect(status().isCreated());

		register("10.0.0.3", "rl-user3@example.com")
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.detail", containsString("Too many register attempts")));

		login("10.0.0.3").andExpect(status().isUnauthorized()); // login budget is separate
	}

	private ResultActions login(String ip) throws Exception {
		return mvc.perform(post("/api/auth/login").with(request -> { request.setRemoteAddr(ip); return request; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}"));
	}

	private ResultActions register(String ip, String email) throws Exception {
		return mvc.perform(post("/api/auth/register").with(request -> { request.setRemoteAddr(ip); return request; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"RL Test\",\"email\":\"" + email + "\",\"password\":\"Rl-User-Pw-123\"}"));
	}
}

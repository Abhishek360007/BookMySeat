package com.abhishekraj.BookMySeat.notification;

import com.abhishekraj.BookMySeat.TestcontainersConfig;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.repository.UserRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.abhishekraj.BookMySeat.notification.BookingEmailTest.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "Forgot password": reveals nothing, single-use short-lived links, every session logged out afterwards
@SpringBootTest(properties = {
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000",
		"email.public-url=https://bookmyseat.test",
		"email.outbox.initial-delay=PT1H",
		"app.demo.email=demo-reset@example.com"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PasswordResetTest.FakeEmail.class})
class PasswordResetTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeEmail {
		@Bean
		@Primary
		FakeEmailSender fakeEmailSender() {
			return new FakeEmailSender();
		}
	}

	static final String EMAIL = "forgetful@example.com";
	static final String OLD = "Old-Password-123";
	static final String NEW = "New-Password-456";
	static final Pattern LINK = Pattern.compile("https://bookmyseat\\.test/#/reset/([A-Za-z0-9_-]{43})");

	@Autowired MockMvc mvc;
	@Autowired FakeEmailSender sender;
	@Autowired OutboxEmailRepository outboxRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void setUp() throws Exception {
		sender.reset();
		register(EMAIL, OLD);
		await(() -> sender.sentTo(EMAIL).size() == 1); // the verification email
		sender.reset();
	}

	@AfterEach
	void cleanUp() {
		outboxRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("rate:*"));
	}

	@Test
	void resetLink_setsTheNewPassword_logsOutEverySession_andIsSingleUse() throws Exception {
		String refreshToken = JsonPath.read(login(EMAIL, OLD).andReturn().getResponse().getContentAsString(), "$.refreshToken");

		forgot(EMAIL).andExpect(status().isAccepted());
		await(() -> sender.sentTo(EMAIL).size() == 1);
		EmailMessage email = sender.sentTo(EMAIL).getFirst();
		assertThat(email.subject()).isEqualTo("Reset your BookMySeat password");
		assertThat(email.text()).contains("expires in 60 minutes");
		String token = tokenIn(email.text());
		assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens where token_hash = ?", Integer.class, token))
				.as("only the hash is stored").isZero();

		reset(token, NEW).andExpect(status().isNoContent());

		login(EMAIL, OLD).andExpect(status().isUnauthorized());
		login(EMAIL, NEW).andExpect(status().isOk());
		mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
						.content("{\"refreshToken\":\"" + refreshToken + "\"}"))
				.andExpect(status().isUnauthorized()); // the old session is gone
		reset(token, "Another-Password-789").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid link"));

		await(() -> sender.sentTo(EMAIL).size() == 2);
		assertThat(sender.sentTo(EMAIL).get(1).subject()).isEqualTo("Your BookMySeat password was changed");
		// Opening the emailed link proved the inbox is theirs
		String access = JsonPath.read(login(EMAIL, NEW).andReturn().getResponse().getContentAsString(), "$.accessToken");
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
				.andExpect(jsonPath("$.emailVerified").value(true));
	}

	@Test
	void unknownAddress_getsTheSameAnswer_andNoEmail() throws Exception {
		forgot("nobody-here@example.com").andExpect(status().isAccepted());
		forgot("FORGETFUL@Example.com").andExpect(status().isAccepted()); // case doesn't matter

		await(() -> sender.calls.get() >= 1);
		Thread.sleep(200);
		assertThat(sender.sent).extracting(EmailMessage::to).containsExactly(EMAIL);
	}

	@Test
	void theDemoAccount_cantBeReset() throws Exception {
		register("demo-reset@example.com", OLD);
		forgot("demo-reset@example.com").andExpect(status().isAccepted());

		Thread.sleep(300);
		assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens", Integer.class)).isZero();
		assertThat(sender.sentTo("demo-reset@example.com")).noneMatch(m -> m.subject().contains("Reset"));
	}

	@Test
	void expiredOrReplacedLinks_areRefused() throws Exception {
		forgot(EMAIL);
		await(() -> sender.sentTo(EMAIL).size() == 1);
		String first = tokenIn(sender.sentTo(EMAIL).getFirst().text());
		forgot(EMAIL);
		await(() -> sender.sentTo(EMAIL).size() == 2);
		String second = tokenIn(sender.sentTo(EMAIL).get(1).text());

		reset(first, NEW).andExpect(status().isBadRequest()); // replaced by the newer link

		jdbc.update("update password_reset_tokens set expires_at = now() - interval '1 second'");
		reset(second, NEW).andExpect(status().isBadRequest());
		login(EMAIL, OLD).andExpect(status().isOk());
	}

	@Test
	void weakOrMissingPassword_is400() throws Exception {
		reset("x".repeat(43), "short").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.newPassword").isNotEmpty());
	}

	@Test
	void forgotPassword_isRateLimited() throws Exception {
		for (int i = 0; i < 3; i++) {
			forgot("someone" + i + "@example.com").andExpect(status().isAccepted());
		}
		forgot(EMAIL).andExpect(status().isTooManyRequests());
	}

	// ---- helpers ----

	ResultActions forgot(String email) throws Exception {
		return mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\"}"));
	}

	ResultActions reset(String token, String password) throws Exception {
		return mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}"));
	}

	ResultActions login(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	void register(String email, String password) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Forgetful\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isCreated());
	}

	static String tokenIn(String text) {
		Matcher matcher = LINK.matcher(text);
		assertThat(matcher.find()).as("reset link in: %s", text).isTrue();
		return matcher.group(1);
	}
}

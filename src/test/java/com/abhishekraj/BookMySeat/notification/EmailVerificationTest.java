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

// Optional email verification: link sent on registration, single use, expires, can be re-sent (rate-limited)
@SpringBootTest(properties = {
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000",
		"email.public-url=https://bookmyseat.test",
		"email.outbox.initial-delay=PT1H"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, EmailVerificationTest.FakeEmail.class})
class EmailVerificationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeEmail {
		@Bean
		@Primary
		FakeEmailSender fakeEmailSender() {
			return new FakeEmailSender();
		}
	}

	static final String EMAIL = "verify-me@example.com";
	static final Pattern LINK = Pattern.compile("https://bookmyseat\\.test/#/verify/([A-Za-z0-9_-]{43})");

	@Autowired MockMvc mvc;
	@Autowired FakeEmailSender sender;
	@Autowired OutboxEmailRepository outboxRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired JdbcTemplate jdbc;

	String user;

	@BeforeEach
	void setUp() throws Exception {
		sender.reset();
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Ravi <b>K</b>\",\"email\":\"" + EMAIL + "\",\"password\":\"Verify-Pw-12345\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.emailVerified").value(false));
		user = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + EMAIL + "\",\"password\":\"Verify-Pw-12345\"}"))
				.andReturn().getResponse().getContentAsString(), "$.accessToken");
	}

	@AfterEach
	void cleanUp() {
		outboxRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("rate:*"));
	}

	@Test
	void registering_sendsALink_andOpeningItVerifiesTheEmail() throws Exception {
		await(() -> sender.sentTo(EMAIL).size() == 1);
		EmailMessage email = sender.sentTo(EMAIL).getFirst();
		assertThat(email.subject()).isEqualTo("Confirm your email for BookMySeat");
		assertThat(email.text()).contains("Hi Ravi <b>K</b>", "expires in 24 hours");
		assertThat(email.html()).contains("Hi Ravi &lt;b&gt;K&lt;/b&gt;").doesNotContain("<b>K</b>");
		String token = tokenIn(email.text());
		assertThat(email.html()).contains("href=\"https://bookmyseat.test/#/verify/" + token + "\"");
		// Only the hash is stored, never the token itself
		assertThat(jdbc.queryForObject("select count(*) from email_verification_tokens where token_hash = ?", Integer.class, token)).isZero();

		me().andExpect(jsonPath("$.emailVerified").value(false));
		verify(token).andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(EMAIL))
				.andExpect(jsonPath("$.emailVerified").value(true));
		me().andExpect(jsonPath("$.emailVerified").value(true));

		// Clicking the link again (or a mail scanner opening it first) is not an error
		verify(token).andExpect(status().isOk());
	}

	@Test
	void unknownOrExpiredLink_is400() throws Exception {
		verify("x".repeat(43)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid link"));

		await(() -> sender.sentTo(EMAIL).size() == 1);
		String token = tokenIn(sender.sentTo(EMAIL).getFirst().text());
		jdbc.update("update email_verification_tokens set expires_at = now() - interval '1 second'");
		verify(token).andExpect(status().isBadRequest());
		me().andExpect(jsonPath("$.emailVerified").value(false));
	}

	@Test
	void resending_replacesTheOldLink() throws Exception {
		await(() -> sender.sentTo(EMAIL).size() == 1);
		String first = tokenIn(sender.sentTo(EMAIL).getFirst().text());

		resend().andExpect(status().isAccepted());
		await(() -> sender.sentTo(EMAIL).size() == 2);
		String second = tokenIn(sender.sentTo(EMAIL).get(1).text());
		assertThat(second).isNotEqualTo(first);

		verify(first).andExpect(status().isBadRequest());
		verify(second).andExpect(status().isOk());

		// Nothing left to verify
		resend().andExpect(status().isConflict());
	}

	@Test
	void resending_isRateLimited() throws Exception {
		for (int i = 0; i < 3; i++) {
			resend().andExpect(status().isAccepted());
		}
		resend().andExpect(status().isTooManyRequests());
	}

	@Test
	void resending_needsALogin() throws Exception {
		mvc.perform(post("/api/users/me/verification-email")).andExpect(status().isUnauthorized());
	}

	@Test
	void theDemoAccount_countsAsVerified_withoutAnEmail() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Demo User\",\"email\":\"demo@bookmyseat.dev\",\"password\":\"DemoPass123\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.emailVerified").value(true));
		Thread.sleep(300);
		assertThat(sender.sentTo("demo@bookmyseat.dev")).isEmpty();
	}

	ResultActions verify(String token) throws Exception {
		return mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + token + "\"}"));
	}

	ResultActions resend() throws Exception {
		return mvc.perform(post("/api/users/me/verification-email").header("Authorization", "Bearer " + user));
	}

	ResultActions me() throws Exception {
		return mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + user));
	}

	static String tokenIn(String text) {
		Matcher matcher = LINK.matcher(text);
		assertThat(matcher.find()).as("verification link in: %s", text).isTrue();
		return matcher.group(1);
	}
}

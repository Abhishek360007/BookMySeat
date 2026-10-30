package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.RefreshToken;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.repository.RefreshTokenRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Same configuration as BookingApiTest, so both share one cached Spring context and one set of containers
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AuthApiTest {

	@Autowired MockMvc mvc;
	@Autowired UserRepository userRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;

	@AfterEach
	void cleanUp() {
		// Refresh tokens go with their user (ON DELETE CASCADE)
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
	}

	@Test
	void login_returnsShortAccessTokenAndRefreshToken() throws Exception {
		register("auth-user@example.com");

		String body = login("auth-user@example.com")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andExpect(jsonPath("$.refreshToken", not(emptyString())))
				.andExpect(jsonPath("$.refreshExpiresIn").value(604800))
				.andReturn().getResponse().getContentAsString();

		// Only a SHA-256 hash is stored, never the token itself
		String raw = JsonPath.read(body, "$.refreshToken");
		assertThat(refreshTokenRepository.findAll()).extracting(RefreshToken::getTokenHash)
				.allMatch(hash -> hash.length() == 64)
				.doesNotContain(raw);
	}

	@Test
	void refresh_rotatesTokens_andNewAccessTokenWorks() throws Exception {
		register("auth-user@example.com");
		String first = refreshTokenOf(login("auth-user@example.com"));

		String body = refresh(first)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken", not(emptyString())))
				.andReturn().getResponse().getContentAsString();
		String second = JsonPath.read(body, "$.refreshToken");
		assertThat(second).isNotEqualTo(first);

		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + JsonPath.read(body, "$.accessToken")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("auth-user@example.com"));

		refresh(second).andExpect(status().isOk()); // the rotated token works once
	}

	@Test
	void reusingARotatedToken_revokesTheWholeSession() throws Exception {
		register("auth-user@example.com");
		String stolen = refreshTokenOf(login("auth-user@example.com"));
		String current = refreshTokenOf(refresh(stolen)); // the real user refreshes first

		refresh(stolen) // someone replays the old token
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(401));

		refresh(current).andExpect(status().isUnauthorized()); // the whole family is now revoked
	}

	@Test
	void logout_revokesSession_andIsIdempotent() throws Exception {
		register("auth-user@example.com");
		String token = refreshTokenOf(login("auth-user@example.com"));

		logout(token).andExpect(status().isNoContent());
		refresh(token).andExpect(status().isUnauthorized());
		logout(token).andExpect(status().isNoContent());
	}

	@Test
	void unknownOrBlankRefreshToken_isRejected() throws Exception {
		refresh("not-a-real-token").andExpect(status().isUnauthorized());
		refresh("").andExpect(status().isBadRequest());
	}

	private void register(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Auth Test\",\"email\":\"" + email + "\",\"password\":\"Auth-User-Pw-123\"}"))
				.andExpect(status().isCreated());
	}

	private ResultActions login(String email) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"Auth-User-Pw-123\"}"));
	}

	private ResultActions refresh(String token) throws Exception {
		return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + token + "\"}"));
	}

	private ResultActions logout(String token) throws Exception {
		return mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + token + "\"}"));
	}

	private static String refreshTokenOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.refreshToken");
	}
}

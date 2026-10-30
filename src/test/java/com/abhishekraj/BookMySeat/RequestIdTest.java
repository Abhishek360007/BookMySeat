package com.abhishekraj.BookMySeat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Same configuration as BookingApiTest, so it reuses that cached context
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestIdTest {

	private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	@Autowired MockMvc mvc;

	@Test
	void everyResponseGetsAGeneratedRequestId() throws Exception {
		mvc.perform(get("/api/venues"))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
	}

	@Test
	void safeIncomingIdIsReused_andAppearsInErrorBodyAndLogs(CapturedOutput output) throws Exception {
		mvc.perform(get("/api/shows/999999").header("X-Request-Id", "trace-abc-123"))
				.andExpect(status().isNotFound())
				.andExpect(header().string("X-Request-Id", "trace-abc-123"))
				.andExpect(jsonPath("$.requestId").value("trace-abc-123"));

		assertThat(output).contains("[trace-abc-123]").contains("GET /api/shows/999999 -> 404");
	}

	@Test
	void unsafeIncomingIdIsReplaced() throws Exception {
		mvc.perform(get("/api/venues").header("X-Request-Id", "evil\nfake log line"))
				.andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
		mvc.perform(get("/api/venues").header("X-Request-Id", "x".repeat(65)))
				.andExpect(header().string("X-Request-Id", not("x".repeat(65))));
	}

	@Test
	void securityErrorsCarryTheRequestIdToo() throws Exception {
		mvc.perform(post("/api/bookings").header("X-Request-Id", "trace-401")
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.requestId").value("trace-401"));
	}
}

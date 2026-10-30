package com.abhishekraj.BookMySeat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The OpenAPI document behind the public Swagger page: grouping, which endpoints need a token, documented headers
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ApiDocsTest {

	@Autowired MockMvc mvc;

	@Test
	void openApiDocument_groupsEndpoints_andMarksPublicOnes() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags[*].name").value(hasItems("Auth", "Shows", "Bookings", "Admin reports")))
				// Global default: a bearer token is required...
				.andExpect(jsonPath("$.security[0].bearerAuth").exists())
				// ...except on public endpoints, which override it with an empty requirement
				.andExpect(jsonPath("$.paths['/api/auth/login'].post.security").value(empty()))
				.andExpect(jsonPath("$.paths['/api/shows/search'].get.security").value(empty()))
				.andExpect(jsonPath("$.paths['/api/shows/{showId}/seats'].get.security").value(empty()))
				.andExpect(jsonPath("$.paths['/api/bookings'].post.security").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/bookings'].post.parameters[*].name").value(hasItem("Idempotency-Key")));
	}
}

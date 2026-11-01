package com.abhishekraj.BookMySeat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The web app is served by the same server without login, and reads its settings from /api/config.
// Same configuration as BookingApiTest (shared context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WebAppTest {

	@Autowired MockMvc mvc;

	@Test
	void webAppIsPublic() throws Exception {
		mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
		mvc.perform(get("/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<title>BookMySeat</title>")));
		mvc.perform(get("/assets/app.js")).andExpect(status().isOk());
		mvc.perform(get("/assets/app.css")).andExpect(status().isOk());
		mvc.perform(get("/favicon.svg")).andExpect(status().isOk());
	}

	@Test
	void configIsPublic_andPaymentsAreOffByDefault() throws Exception {
		mvc.perform(get("/api/config"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentsEnabled").value(false))
				.andExpect(jsonPath("$.razorpayKeyId").doesNotExist())
				.andExpect(jsonPath("$.currency").value("INR"))
				.andExpect(jsonPath("$.holdMinutes").value(5));
	}
}

package com.abhishekraj.BookMySeat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

	@Test
	void webAppPages_getAStrictContentSecurityPolicy() throws Exception {
		mvc.perform(get("/index.html"))
				.andExpect(header().string("Content-Security-Policy", allOf(
						containsString("default-src 'self'"),
						containsString("script-src 'self' https://checkout.razorpay.com https://cdn.razorpay.com;"),
						containsString("object-src 'none'"),
						containsString("frame-ancestors 'none'"),
						not(containsString("unsafe-eval")))))
				// Inline code never runs; only Razorpay's injected styles are allowed inline
				.andExpect(header().string("Content-Security-Policy", not(containsString("script-src 'self' 'unsafe-inline'"))))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
				.andExpect(header().string("Permissions-Policy", containsString("camera=()")));
	}

	@Test
	void apiResponses_getTheHeadersToo_butSwaggerUiKeepsItsInlineAssets() throws Exception {
		mvc.perform(get("/api/config"))
				.andExpect(header().exists("Content-Security-Policy"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"));
		mvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Content-Security-Policy"))
				.andExpect(header().string("X-Frame-Options", "DENY"));
	}

	// The policy forbids inline code and styles; this keeps them from creeping back into the web app's files
	@Test
	void staticFiles_haveNoInlineScriptsStylesOrEventHandlers() throws Exception {
		Path dir = Path.of("src/main/resources/static");
		List<String> offenders = new ArrayList<>();
		Pattern inline = Pattern.compile("style=\\$?\"|<script(?![^>]*\\bsrc=)[^>]*>|\\son[a-z]+=\"|javascript:");
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path file : files.filter(f -> f.toString().matches(".*\\.(html|js)$")).toList()) {
				List<String> lines = Files.readAllLines(file);
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i);
					if (!line.trim().startsWith("//") && inline.matcher(line).find()) {
						offenders.add(dir.relativize(file) + ":" + (i + 1) + ": " + line.trim());
					}
				}
			}
		}
		assertThat(offenders).isEmpty();
	}
}

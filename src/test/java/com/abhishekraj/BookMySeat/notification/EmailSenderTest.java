package com.abhishekraj.BookMySeat.notification;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

// The two real senders: SMTP into a real Mailpit, and Brevo's HTTP API against a mock server
@Testcontainers
class EmailSenderTest {

	@Container
	static final GenericContainer<?> mailpit = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.27"))
			.withExposedPorts(1025, 8025);

	static final EmailProperties PROPERTIES = new EmailProperties(EmailProperties.Provider.SMTP,
			"no-reply@bookmyseat.test", "BookMySeat", null, null, null, new EmailProperties.Brevo("brevo-test-key", "https://brevo.test/v3"), null);

	static final EmailMessage MESSAGE = new EmailMessage("guest@example.com", "Booking confirmed: Dune ₹500",
			"Seats C5, C6", "<p>Seats <b>C5, C6</b></p>");

	@Test
	void smtp_deliversTextAndHtml() throws Exception {
		JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
		mailSender.setHost(mailpit.getHost());
		mailSender.setPort(mailpit.getMappedPort(1025));

		new SmtpEmailSender(mailSender, PROPERTIES).send(MESSAGE);

		String list = mailpitGet("/api/v1/messages");
		assertThat(JsonPath.<Integer>read(list, "$.total")).isEqualTo(1);
		assertThat(JsonPath.<String>read(list, "$.messages[0].Subject")).isEqualTo("Booking confirmed: Dune ₹500");
		assertThat(JsonPath.<String>read(list, "$.messages[0].From.Address")).isEqualTo("no-reply@bookmyseat.test");
		assertThat(JsonPath.<String>read(list, "$.messages[0].From.Name")).isEqualTo("BookMySeat");
		assertThat(JsonPath.<String>read(list, "$.messages[0].To[0].Address")).isEqualTo("guest@example.com");

		String message = mailpitGet("/api/v1/message/" + JsonPath.read(list, "$.messages[0].ID"));
		assertThat(JsonPath.<String>read(message, "$.Text")).contains("Seats C5, C6");
		assertThat(JsonPath.<String>read(message, "$.HTML")).contains("<b>C5, C6</b>");
	}

	@Test
	void brevo_postsTheEmailWithTheApiKey() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://brevo.test/v3/smtp/email"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("api-key", "brevo-test-key"))
				.andExpect(jsonPath("$.sender.email").value("no-reply@bookmyseat.test"))
				.andExpect(jsonPath("$.sender.name").value("BookMySeat"))
				.andExpect(jsonPath("$.to[0].email").value("guest@example.com"))
				.andExpect(jsonPath("$.subject").value("Booking confirmed: Dune ₹500"))
				.andExpect(jsonPath("$.textContent").value("Seats C5, C6"))
				.andExpect(jsonPath("$.htmlContent").value("<p>Seats <b>C5, C6</b></p>"))
				.andRespond(withStatus(HttpStatus.CREATED));

		new BrevoEmailSender(builder, PROPERTIES).send(MESSAGE);
		server.verify();
	}

	@Test
	void brevo_rejection_throwsSoTheOutboxRetries() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://brevo.test/v3/smtp/email")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(() -> new BrevoEmailSender(builder, PROPERTIES).send(MESSAGE))
				.isInstanceOf(HttpClientErrorException.Unauthorized.class);
	}

	private static String mailpitGet(String path) throws Exception {
		URI uri = URI.create("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025) + path);
		try (HttpClient http = HttpClient.newHttpClient()) {
			return http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body();
		}
	}
}

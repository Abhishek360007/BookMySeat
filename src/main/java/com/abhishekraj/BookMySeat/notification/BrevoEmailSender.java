package com.abhishekraj.BookMySeat.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;

// Brevo transactional email API (https://developers.brevo.com/reference/sendtransacemail): HTTPS, so it
// works where outgoing SMTP is blocked. The sender address must be verified in the Brevo account.
public class BrevoEmailSender implements EmailSender {

	private final RestClient client;
	private final EmailProperties properties;

	public BrevoEmailSender(RestClient.Builder builder, EmailProperties properties) {
		this.properties = properties;
		this.client = builder
				.baseUrl(properties.brevo().baseUrl())
				.defaultHeader("api-key", properties.brevo().apiKey())
				.build();
	}

	private record Address(String email, String name) {}

	private record BrevoAttachment(String name, String content) {} // content: base64

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record SendRequest(Address sender, List<Address> to, String subject, String textContent, String htmlContent,
			List<BrevoAttachment> attachment) {}

	@Override
	public void send(EmailMessage message) {
		client.post().uri("/smtp/email")
				.contentType(MediaType.APPLICATION_JSON)
				.body(new SendRequest(new Address(properties.from(), properties.fromName()),
						List.of(new Address(message.to(), null)), message.subject(), message.text(), message.html(),
						message.attachment() == null ? null : List.of(new BrevoAttachment(message.attachment().filename(),
								Base64.getEncoder().encodeToString(message.attachment().content())))))
				.retrieve()
				.toBodilessEntity(); // a 4xx/5xx throws, and the outbox retries
	}
}

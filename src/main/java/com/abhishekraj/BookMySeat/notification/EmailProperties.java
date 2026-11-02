package com.abhishekraj.BookMySeat.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** email.* settings: which provider delivers emails, and the sender shown to recipients. */
@ConfigurationProperties("email")
public record EmailProperties(Provider provider, String from, String fromName, Brevo brevo) {

	public enum Provider { LOG, SMTP, BREVO }

	public record Brevo(String apiKey, String baseUrl) {}
}

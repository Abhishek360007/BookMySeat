package com.abhishekraj.BookMySeat.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** email.* settings: which provider delivers emails, the sender shown to recipients, and outbox retries. */
@ConfigurationProperties("email")
public record EmailProperties(Provider provider, String from, String fromName, Brevo brevo, Outbox outbox) {

	public enum Provider { LOG, SMTP, BREVO }

	public record Brevo(String apiKey, String baseUrl) {}

	/**
	 * sendImmediately: try delivering right after the transaction commits instead of waiting for the next job run.
	 * keepSent: how long delivered emails stay in the table (their bodies can hold one-time links).
	 */
	public record Outbox(boolean sendImmediately, int maxAttempts, Duration firstRetryDelay, Duration keepSent) {}
}

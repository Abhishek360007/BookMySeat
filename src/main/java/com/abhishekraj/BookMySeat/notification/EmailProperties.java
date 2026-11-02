package com.abhishekraj.BookMySeat.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Set;

/**
 * email.* settings: which provider delivers emails, the sender shown to recipients, and outbox retries.
 * publicUrl is where links in emails point (the web app); timeZone is how show times are written.
 * suppressedRecipients never get emails, e.g. the shared public demo account, whose inbox nobody owns.
 */
@ConfigurationProperties("email")
public record EmailProperties(Provider provider, String from, String fromName, String publicUrl, ZoneId timeZone,
		Set<String> suppressedRecipients, Brevo brevo, Outbox outbox) {

	public enum Provider { LOG, SMTP, BREVO }

	public record Brevo(String apiKey, String baseUrl) {}

	/**
	 * sendImmediately: try delivering right after the transaction commits instead of waiting for the next job run.
	 * keepSent: how long delivered emails stay in the table (their bodies can hold one-time links).
	 */
	public record Outbox(boolean sendImmediately, int maxAttempts, Duration firstRetryDelay, Duration keepSent) {}
}

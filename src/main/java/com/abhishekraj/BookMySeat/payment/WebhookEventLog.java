package com.abhishekraj.BookMySeat.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

// Remembers processed webhook event ids (table from V7)
@Component
public class WebhookEventLog {

	private final JdbcTemplate jdbc;

	public WebhookEventLog(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Records the event; false if it was already recorded. Runs in the caller's transaction, so a failed
	 * processing attempt rolls the record back and Razorpay's retry is processed again. A concurrent duplicate
	 * waits on the unique key until the first one commits, then sees the conflict.
	 */
	public boolean recordFirstDelivery(String eventId, String eventType, Instant now) {
		return jdbc.update("""
				INSERT INTO payment_webhook_events (event_id, event_type, received_at) VALUES (?, ?, ?)
				ON CONFLICT (event_id) DO NOTHING
				""", eventId, eventType, Timestamp.from(now)) == 1;
	}
}

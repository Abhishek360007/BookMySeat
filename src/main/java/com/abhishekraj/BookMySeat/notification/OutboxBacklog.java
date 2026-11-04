package com.abhishekraj.BookMySeat.notification;

import java.time.Instant;

/** What is still waiting in the outbox. oldestPending is null when nothing is pending. */
public record OutboxBacklog(long pending, Instant oldestPending, long failed) {}

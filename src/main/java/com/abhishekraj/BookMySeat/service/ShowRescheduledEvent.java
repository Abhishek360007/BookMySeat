package com.abhishekraj.BookMySeat.service;

import java.time.Instant;

/** Published inside the transaction that moves a show to a new time, so the customer emails commit with it. */
public record ShowRescheduledEvent(Long showId, Instant previousStartTime) {}

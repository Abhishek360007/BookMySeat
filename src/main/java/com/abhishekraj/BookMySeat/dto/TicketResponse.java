package com.abhishekraj.BookMySeat.dto;

import java.time.Instant;
import java.util.List;

/** What a ticket shows: the code in its QR, and enough to read at the door without scanning. */
public record TicketResponse(Long bookingId, String code, String showTitle, String venueName, Instant showStartTime,
		List<String> seats, String holderName, Instant checkedInAt) {}

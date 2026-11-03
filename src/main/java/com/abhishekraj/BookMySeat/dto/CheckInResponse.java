package com.abhishekraj.BookMySeat.dto;

import java.time.Instant;
import java.util.List;

public record CheckInResponse(Long bookingId, String holderName, List<String> seats, String showTitle, Instant checkedInAt) {}

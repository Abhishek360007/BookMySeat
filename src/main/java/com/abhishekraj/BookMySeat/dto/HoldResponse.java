package com.abhishekraj.BookMySeat.dto;

import java.time.Instant;
import java.util.List;

public record HoldResponse(Long showId, List<Long> seatIds, List<String> seatNumbers, Instant expiresAt) {}

package com.abhishekraj.BookMySeat.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything the client needs to open Razorpay Checkout: keyId + orderId + amount (in paise).
 * The seats stay held for this user until expiresAt.
 */
public record CheckoutResponse(String orderId, String razorpayKeyId, long amount, String currency,
		BigDecimal totalAmount, Long showId, List<Long> seatIds, Instant expiresAt) {}

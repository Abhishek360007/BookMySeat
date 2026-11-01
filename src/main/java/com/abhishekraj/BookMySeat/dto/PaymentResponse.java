package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Payment;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(String orderId, PaymentStatus status, BigDecimal amount, String currency,
		String paymentId, Long bookingId, String refundId, String failureReason, Instant expiresAt) {

	public static PaymentResponse from(Payment payment) {
		return new PaymentResponse(payment.getOrderId(), payment.getStatus(), payment.getAmount(), payment.getCurrency(),
				payment.getPaymentId(), payment.getBooking() == null ? null : payment.getBooking().getId(),
				payment.getRefundId(), payment.getFailureReason(), payment.getExpiresAt());
	}
}

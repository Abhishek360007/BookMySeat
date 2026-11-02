package com.abhishekraj.BookMySeat.payment;

/** Published inside the transaction that records a completed refund. */
public record PaymentRefundedEvent(String orderId) {}

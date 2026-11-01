package com.abhishekraj.BookMySeat.entity;

public enum PaymentStatus {
	PENDING,        // Razorpay order created, waiting for the customer to pay
	CONFIRMED,      // paid and booked
	EXPIRED,        // not paid within the payment window; holds released
	REFUND_PENDING, // money taken but no booking (seats gone) or booking cancelled; refund not yet confirmed
	REFUNDED
}

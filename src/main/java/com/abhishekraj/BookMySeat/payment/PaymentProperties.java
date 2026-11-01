package com.abhishekraj.BookMySeat.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * payments.* settings. With enabled=false (the default) bookings are confirmed directly, as before;
 * with enabled=true a booking only exists after a Razorpay payment.
 */
@ConfigurationProperties("payments")
public record PaymentProperties(boolean enabled, Duration window, String currency, Razorpay razorpay) {

	public record Razorpay(String keyId, String keySecret, String webhookSecret, String baseUrl) {}
}

package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.payment.PaymentProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Config", description = "Public settings the web app needs.")
@SecurityRequirements
@RestController
@RequestMapping("/api/config")
public class ConfigController {

	private final PaymentProperties payments;
	private final long holdMinutes;

	public ConfigController(PaymentProperties payments, @Value("${booking.hold-minutes}") long holdMinutes) {
		this.payments = payments;
		this.holdMinutes = holdMinutes;
	}

	public record ClientConfig(boolean paymentsEnabled, String razorpayKeyId, String currency, long holdMinutes) {}

	// The key id is public by design (Razorpay Checkout runs in the browser); the key secret never leaves the server
	@Operation(summary = "Web app settings", description = "Whether bookings need an online payment, and the public Razorpay key id")
	@GetMapping
	public ClientConfig config() {
		return new ClientConfig(payments.enabled(), payments.enabled() ? payments.razorpay().keyId() : null,
				payments.currency(), holdMinutes);
	}
}

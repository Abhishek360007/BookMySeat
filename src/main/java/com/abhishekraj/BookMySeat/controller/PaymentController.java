package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.PaymentResponse;
import com.abhishekraj.BookMySeat.dto.VerifyPaymentRequest;
import com.abhishekraj.BookMySeat.payment.PaymentService;
import com.abhishekraj.BookMySeat.payment.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Payments", description = "Razorpay payments for a checkout started with POST /api/bookings/checkout.")
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

	private final PaymentService paymentService;
	private final WebhookService webhookService;

	@Operation(summary = "Report a successful Razorpay payment",
			description = "Send the razorpay_order_id, razorpay_payment_id and razorpay_signature from Checkout. "
					+ "Repeating it is safe: the same booking is returned.")
	@PostMapping("/verify")
	public PaymentResponse verify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VerifyPaymentRequest request) {
		return paymentService.verify(Long.parseLong(jwt.getSubject()), request);
	}

	// Called by Razorpay, not by users: no JWT, authenticated by the HMAC signature instead
	@Operation(summary = "Razorpay webhook", description = "Server-to-server payment events, verified with X-Razorpay-Signature.")
	@SecurityRequirements
	@PostMapping("/webhook")
	public Map<String, String> webhook(@RequestBody byte[] rawBody,
			@RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
			@RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
		return Map.of("result", webhookService.handle(rawBody, signature, eventId).name());
	}

	@Operation(summary = "Payment status", description = "PENDING, CONFIRMED (with bookingId), EXPIRED, REFUND_PENDING or REFUNDED")
	@GetMapping("/{orderId}")
	public PaymentResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId) {
		return paymentService.status(Long.parseLong(jwt.getSubject()), orderId);
	}
}

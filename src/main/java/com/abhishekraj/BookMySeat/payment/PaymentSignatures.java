package com.abhishekraj.BookMySeat.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Razorpay's HMAC-SHA256 signatures, compared in constant time so timing can't leak the expected value. */
public final class PaymentSignatures {

	private PaymentSignatures() {}

	/** Checkout success callback: HMAC(order_id + "|" + payment_id) with the API key secret. */
	public static boolean isValidPayment(String orderId, String paymentId, String signature, String keySecret) {
		return matches(hmacSha256Hex(keySecret, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)), signature);
	}

	/** Webhook: HMAC of the exact raw request body with the webhook secret (header X-Razorpay-Signature). */
	public static boolean isValidWebhook(byte[] rawBody, String signature, String webhookSecret) {
		return matches(hmacSha256Hex(webhookSecret, rawBody), signature);
	}

	static String hmacSha256Hex(String secret, byte[] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(data));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is always available", e);
		}
	}

	private static boolean matches(String expectedHex, String actual) {
		return actual != null && MessageDigest.isEqual(
				expectedHex.getBytes(StandardCharsets.UTF_8), actual.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
	}
}

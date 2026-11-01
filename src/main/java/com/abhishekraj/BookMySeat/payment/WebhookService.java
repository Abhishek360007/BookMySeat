package com.abhishekraj.BookMySeat.payment;

import com.abhishekraj.BookMySeat.exception.InvalidSignatureException;
import com.abhishekraj.BookMySeat.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Razorpay webhooks: the server-to-server report of a payment, which arrives even if the customer closes the
 * browser before the client callback. Delivered at least once, so every event is processed at most once.
 */
@Slf4j
@Service
public class WebhookService {

	public enum Outcome { PROCESSED, DUPLICATE, IGNORED }

	private final PaymentProperties properties;
	private final PaymentConfirmationService confirmation;
	private final PaymentRepository paymentRepository;
	private final WebhookEventLog eventLog;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	public WebhookService(PaymentProperties properties, PaymentConfirmationService confirmation,
			PaymentRepository paymentRepository, WebhookEventLog eventLog, JsonMapper jsonMapper, Clock clock) {
		this.properties = properties;
		this.confirmation = confirmation;
		this.paymentRepository = paymentRepository;
		this.eventLog = eventLog;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	@Transactional
	public Outcome handle(byte[] rawBody, String signature, String eventId) {
		if (!properties.enabled()) {
			return Outcome.IGNORED;
		}
		// Verified over the exact bytes received: re-serialising the JSON first would change the signature
		if (!PaymentSignatures.isValidWebhook(rawBody, signature, properties.razorpay().webhookSecret())) {
			throw new InvalidSignatureException("Webhook signature is invalid");
		}

		JsonNode event = jsonMapper.readTree(rawBody);
		String type = event.path("event").asText("");
		JsonNode entity = event.path("payload").path("payment").path("entity");
		String orderId = entity.path("order_id").asText("");
		String paymentId = entity.path("id").asText("");
		String id = eventId != null && !eventId.isBlank() ? eventId : sha256(rawBody); // Razorpay sends x-razorpay-event-id

		if (!eventLog.recordFirstDelivery(id, type, Instant.now(clock))) {
			log.info("Webhook {} ({}) already processed", id, type);
			return Outcome.DUPLICATE;
		}
		if (orderId.isBlank() || !paymentRepository.existsByOrderId(orderId)) {
			log.info("Webhook {} ({}) is not about one of our orders; ignored", id, type);
			return Outcome.IGNORED;
		}

		switch (type) {
			case "payment.captured", "order.paid" -> confirmation.paymentSucceeded(orderId, paymentId, "webhook");
			// The customer can retry on the same order, so a failed attempt only gets recorded;
			// the expiry job releases the seats if nobody pays in time
			case "payment.failed" -> confirmation.paymentFailed(orderId, entity.path("error_description").asText("Payment failed"));
			default -> {
				return Outcome.IGNORED;
			}
		}
		return Outcome.PROCESSED;
	}

	private static String sha256(byte[] body) {
		try {
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}

package com.abhishekraj.BookMySeat.payment;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

// Razorpay REST API (https://razorpay.com/docs/api/): Basic auth with key id + key secret, amounts in paise
public class RazorpayGateway implements PaymentGateway {

	private final RestClient client;

	public RazorpayGateway(PaymentProperties.Razorpay config) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(5));
		requestFactory.setReadTimeout(Duration.ofSeconds(15));
		String credentials = Base64.getEncoder().encodeToString(
				(config.keyId() + ":" + config.keySecret()).getBytes(StandardCharsets.UTF_8));
		this.client = RestClient.builder()
				.baseUrl(config.baseUrl())
				.requestFactory(requestFactory)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
				.build();
	}

	private record OrderResponse(String id, long amount, String currency) {}

	private record RefundResponse(String id) {}

	@Override
	public Order createOrder(long amountMinor, String currency, String receipt) {
		OrderResponse order = client.post().uri("/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("amount", amountMinor, "currency", currency, "receipt", receipt))
				.retrieve()
				.body(OrderResponse.class);
		return new Order(order.id(), order.amount(), order.currency());
	}

	@Override
	public String refund(String paymentId, long amountMinor) {
		return client.post().uri("/payments/{id}/refund", paymentId)
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("amount", amountMinor))
				.retrieve()
				.body(RefundResponse.class)
				.id();
	}
}

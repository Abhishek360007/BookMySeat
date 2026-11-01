package com.abhishekraj.BookMySeat.payment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

// Stands in for Razorpay in tests: hands out order ids and records refunds
public class FakePaymentGateway implements PaymentGateway {

	public record Refund(String paymentId, long amountMinor, String refundId) {}

	private final AtomicInteger sequence = new AtomicInteger();
	public final List<Order> orders = new CopyOnWriteArrayList<>();
	public final List<Refund> refunds = new CopyOnWriteArrayList<>();
	public volatile boolean failRefunds = false;

	@Override
	public Order createOrder(long amountMinor, String currency, String receipt) {
		Order order = new Order("order_test_" + sequence.incrementAndGet(), amountMinor, currency);
		orders.add(order);
		return order;
	}

	@Override
	public String refund(String paymentId, long amountMinor) {
		if (failRefunds) {
			throw new IllegalStateException("Razorpay is down (simulated)");
		}
		Refund refund = new Refund(paymentId, amountMinor, "rfnd_test_" + sequence.incrementAndGet());
		refunds.add(refund);
		return refund.refundId();
	}

	public void reset() {
		orders.clear();
		refunds.clear();
		failRefunds = false;
	}
}

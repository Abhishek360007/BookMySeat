package com.abhishekraj.BookMySeat.payment;

/** The payment provider, behind an interface so tests can use a fake instead of calling Razorpay. */
public interface PaymentGateway {

	record Order(String id, long amountMinor, String currency) {}

	/** Creates a provider-side order; the customer pays against its id. Amounts are in paise. */
	Order createOrder(long amountMinor, String currency, String receipt);

	/** Refunds a captured payment in full; returns the provider's refund id. */
	String refund(String paymentId, long amountMinor);
}

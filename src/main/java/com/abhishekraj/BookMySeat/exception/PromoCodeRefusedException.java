package com.abhishekraj.BookMySeat.exception;

/** A promo code that doesn't exist or can't be used on this order (422, with the reason as the detail). */
public class PromoCodeRefusedException extends RuntimeException {
	public PromoCodeRefusedException(String message) {
		super(message);
	}
}

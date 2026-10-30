package com.abhishekraj.BookMySeat.exception;

public class IdempotencyKeyReusedException extends RuntimeException {
	public IdempotencyKeyReusedException() {
		super("This Idempotency-Key was already used for a different request. Use a new key for new seats.");
	}
}

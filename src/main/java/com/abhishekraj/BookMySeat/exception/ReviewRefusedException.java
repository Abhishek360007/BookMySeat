package com.abhishekraj.BookMySeat.exception;

import com.abhishekraj.BookMySeat.service.ReviewRules;

/** Someone who can't review this show (yet): no booking for it, or it hasn't started. reason is machine-readable. */
public class ReviewRefusedException extends RuntimeException {

	private final ReviewRules.Refusal reason;

	public ReviewRefusedException(ReviewRules.Refusal reason, String message) {
		super(message);
		this.reason = reason;
	}

	public ReviewRules.Refusal getReason() {
		return reason;
	}
}

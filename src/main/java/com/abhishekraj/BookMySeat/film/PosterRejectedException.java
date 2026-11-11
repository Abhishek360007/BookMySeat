package com.abhishekraj.BookMySeat.film;

/** An upload that can't be a poster. reason is machine-readable; the message says what to do instead. */
public class PosterRejectedException extends RuntimeException {

	public enum Reason { NOT_AN_IMAGE, UNSUPPORTED_TYPE, TOO_SMALL, TOO_LARGE }

	private final Reason reason;

	public PosterRejectedException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}
}

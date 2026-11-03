package com.abhishekraj.BookMySeat.exception;

import java.time.Instant;

/** A genuine ticket that can't be let in (already used, cancelled, another show...). reason is machine-readable. */
public class CheckInRefusedException extends RuntimeException {

	public enum Reason { ALREADY_CHECKED_IN, CANCELLED, OTHER_SHOW, TOO_EARLY, TOO_LATE }

	private final Reason reason;
	private final Instant checkedInAt;

	public CheckInRefusedException(Reason reason, String message, Instant checkedInAt) {
		super(message);
		this.reason = reason;
		this.checkedInAt = checkedInAt;
	}

	public Reason getReason() {
		return reason;
	}

	public Instant getCheckedInAt() {
		return checkedInAt;
	}
}

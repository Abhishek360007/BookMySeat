package com.abhishekraj.BookMySeat.entity;

/** WAITING and OFFERED are a place in line; the others are how it ended. */
public enum WaitlistStatus {
	WAITING,
	OFFERED, // seats are held for this person until the offer expires
	CLAIMED, // they booked
	EXPIRED, // the offer ran out
	LEFT,    // they left the line (or declined the offer)
	CLOSED;  // the show was cancelled or started

	public boolean isActive() {
		return this == WAITING || this == OFFERED;
	}
}

package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.entity.WaitlistStatus;
import com.abhishekraj.BookMySeat.support.AfterCommit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * waitlist.joined, and waitlist.offers by result: offered, then claimed / expired / declined / closed.
 * Counted after commit, created at 0 on startup (see BookingMetrics), tags never carry show or user ids.
 */
@Component
public class WaitlistMetrics {

	private final MeterRegistry meterRegistry;

	public WaitlistMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
		meterRegistry.counter("waitlist.joined");
		for (String result : new String[] {"offered", "claimed", "expired", "declined", "closed"}) {
			meterRegistry.counter("waitlist.offers", "result", result);
		}
	}

	public void joined() {
		AfterCommit.run(() -> meterRegistry.counter("waitlist.joined").increment());
	}

	public void offered() {
		count("offered");
	}

	/** How an offer ended (only offers count: a WAITING entry that leaves never had one). */
	public void offerEnded(WaitlistStatus how) {
		String result = switch (how) {
			case CLAIMED -> "claimed";
			case EXPIRED -> "expired";
			case LEFT -> "declined";
			case CLOSED -> "closed";
			default -> throw new IllegalArgumentException("Not an end: " + how);
		};
		count(result);
	}

	private void count(String result) {
		AfterCommit.run(() -> meterRegistry.counter("waitlist.offers", "result", result.toLowerCase(Locale.ROOT)).increment());
	}
}

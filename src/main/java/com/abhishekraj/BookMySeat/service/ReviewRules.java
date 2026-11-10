package com.abhishekraj.BookMySeat.service;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * The rules of reviews, in plain Java so each one is unit-tested: who may review, what a review's text becomes,
 * how the average is shown and how a reviewer's name is shown.
 */
public final class ReviewRules {

	public static final int MAX_TEXT = 1000;

	/** Why someone can't review a show. */
	public enum Refusal { NO_BOOKING, NOT_STARTED }

	private ReviewRules() {
	}

	/** Only people who went: a confirmed booking for the show, and the show has started. */
	public static Optional<Refusal> refusal(boolean hasConfirmedBooking, Instant showStart, Instant now) {
		if (!hasConfirmedBooking) {
			return Optional.of(Refusal.NO_BOOKING);
		}
		if (now.isBefore(showStart)) {
			return Optional.of(Refusal.NOT_STARTED);
		}
		return Optional.empty();
	}

	/**
	 * The text as stored: trimmed, with Windows and old Mac line breaks made plain, at most two blank lines in a row,
	 * no control characters other than line breaks and tabs; nothing left means no text (null).
	 */
	public static String text(String raw) {
		if (raw == null) {
			return null;
		}
		String text = raw.replace("\r\n", "\n").replace('\r', '\n')
				.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "")
				.replaceAll("\\n{4,}", "\n\n\n")
				.strip();
		return text.isEmpty() ? null : text;
	}

	/** One decimal, e.g. 4.3; null while there are no ratings. */
	public static Double average(int sum, int count) {
		if (count <= 0) {
			return null;
		}
		return Math.round(sum * 10.0 / count) / 10.0;
	}

	/** "Asha Kumar" is shown as "Asha K.": enough to be a person, not enough to look them up. */
	public static String displayName(String name) {
		if (name == null || name.isBlank()) {
			return "A guest";
		}
		String[] parts = name.strip().split("\\s+");
		if (parts.length == 1) {
			return parts[0];
		}
		String last = parts[parts.length - 1];
		return parts[0] + " " + last.substring(0, last.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT) + ".";
	}
}

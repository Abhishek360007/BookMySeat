package com.abhishekraj.BookMySeat.ticket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ticket codes: "T1.<booking id>.<signature>", the signature being the first 128 bits of
 * HMAC-SHA256(key, "ticket:<booking id>"). Nobody can make a valid code for a booking from its id alone, so
 * the code in the QR is the ticket; whether it was already used or cancelled is checked in the database.
 */
@Component
public class TicketCodes {

	private static final Pattern FORMAT = Pattern.compile("T1\\.(\\d{1,18})\\.([A-Za-z0-9_-]{22})");
	private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

	private final byte[] key;

	/**
	 * tickets.secret (TICKET_SECRET) if set; otherwise a key derived from the JWT secret, so a deployment needs
	 * no extra variable. Changing either secret makes the tickets already issued invalid.
	 */
	public TicketCodes(@Value("${tickets.secret:}") String ticketSecret, @Value("${jwt.secret}") String jwtSecret) {
		this.key = ticketSecret.isBlank()
				? hmac(jwtSecret.getBytes(StandardCharsets.UTF_8), "bookmyseat-ticket-key-v1")
				: ticketSecret.getBytes(StandardCharsets.UTF_8);
	}

	public String codeFor(long bookingId) {
		return "T1." + bookingId + "." + signature(bookingId);
	}

	/** The booking id of a genuine code; empty for anything malformed or forged. */
	public Optional<Long> bookingIdOf(String code) {
		Matcher matcher = FORMAT.matcher(code == null ? "" : code.trim());
		if (!matcher.matches()) {
			return Optional.empty();
		}
		long bookingId = Long.parseLong(matcher.group(1));
		byte[] expected = signature(bookingId).getBytes(StandardCharsets.US_ASCII);
		byte[] given = matcher.group(2).getBytes(StandardCharsets.US_ASCII);
		return MessageDigest.isEqual(expected, given) ? Optional.of(bookingId) : Optional.empty(); // constant time
	}

	private String signature(long bookingId) {
		return BASE64URL.encodeToString(Arrays.copyOf(hmac(key, "ticket:" + bookingId), 16));
	}

	private static byte[] hmac(byte[] key, String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is always available", e);
		}
	}
}

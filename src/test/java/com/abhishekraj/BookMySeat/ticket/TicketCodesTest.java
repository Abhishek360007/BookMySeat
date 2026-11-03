package com.abhishekraj.BookMySeat.ticket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Ticket codes on their own: no Spring, milliseconds
class TicketCodesTest {

	static final String JWT_SECRET = "test-jwt-secret-0123456789-abcdefghijklmnop";
	final TicketCodes codes = new TicketCodes("", JWT_SECRET);

	@Test
	void aCode_namesItsBooking_andVerifies() {
		String code = codes.codeFor(42);
		assertThat(code).matches("T1\\.42\\.[A-Za-z0-9_-]{22}");
		assertThat(codes.bookingIdOf(code)).contains(42L);
		assertThat(codes.bookingIdOf("  " + code + "\n")).contains(42L); // scanners add whitespace
	}

	@Test
	void anotherBookingsSignature_tamperingAndGarbage_areRejected() {
		String code42 = codes.codeFor(42);
		String signature = code42.substring(code42.lastIndexOf('.') + 1);
		assertThat(codes.bookingIdOf("T1.43." + signature)).isEmpty(); // same signature, other booking
		char last = signature.charAt(21);
		assertThat(codes.bookingIdOf("T1.42." + signature.substring(0, 21) + (last == 'A' ? 'B' : 'A'))).isEmpty();
		assertThat(codes.bookingIdOf("T1.42")).isEmpty();
		assertThat(codes.bookingIdOf("")).isEmpty();
		assertThat(codes.bookingIdOf(null)).isEmpty();
		assertThat(codes.bookingIdOf("https://example.com/?T1.42." + signature)).isEmpty();
	}

	@Test
	void codesFromAnotherKey_areRejected() {
		TicketCodes otherServer = new TicketCodes("", "another-jwt-secret-0123456789-abcdefghijklm");
		TicketCodes explicitKey = new TicketCodes("a-dedicated-ticket-secret-of-32-bytes!!", JWT_SECRET);

		assertThat(codes.bookingIdOf(otherServer.codeFor(42))).isEmpty();
		assertThat(codes.bookingIdOf(explicitKey.codeFor(42))).isEmpty();
		assertThat(explicitKey.bookingIdOf(explicitKey.codeFor(42))).contains(42L);
	}
}

package com.abhishekraj.BookMySeat.ticket;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventsTest {

	static final CalendarEvents.Event EVENT = new CalendarEvents.Event("booking-7@bookmyseat", 29_000_000,
			Instant.parse("2026-11-10T09:15:30Z"), Instant.parse("2026-11-20T13:30:00Z"), Duration.ofHours(3),
			"Dune; Part Two, IMAX", "PVR Phoenix, Pune", "Booking #7\nSeats C5, C6", "http://localhost:8080/#/bookings",
			Duration.ofHours(1));

	@Test
	void writesOneEventInUtc_withEveryLineEndingInCrlf() {
		String ics = new String(CalendarEvents.ics(EVENT), StandardCharsets.UTF_8);

		assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BookMySeat//Tickets//EN\r\n")
				.endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n")
				.contains("METHOD:PUBLISH\r\n", "UID:booking-7@bookmyseat\r\n", "SEQUENCE:29000000\r\n",
						"DTSTAMP:20261110T091530Z\r\n", "DTSTART:20261120T133000Z\r\n", "DTEND:20261120T163000Z\r\n",
						"URL:http://localhost:8080/#/bookings\r\n", "STATUS:CONFIRMED\r\n");
		assertThat(ics.replace("\r\n", "")).doesNotContain("\r", "\n"); // no bare line breaks anywhere
	}

	@Test
	void escapesText_soCommasSemicolonsAndLineBreaksStayInTheirField() {
		String ics = new String(CalendarEvents.ics(EVENT), StandardCharsets.UTF_8);

		assertThat(ics).contains("SUMMARY:Dune\\; Part Two\\, IMAX\r\n", "LOCATION:PVR Phoenix\\, Pune\r\n",
				"DESCRIPTION:Booking #7\\nSeats C5\\, C6\r\n");
		assertThat(CalendarEvents.text("a\\b")).isEqualTo("a\\\\b");
		assertThat(CalendarEvents.text("one\r\ntwo\rthree")).isEqualTo("one\\ntwo\\nthree");
	}

	@Test
	void remindsAnHourBefore_andLeavesTheReminderOutWhenThereIsNone() {
		assertThat(new String(CalendarEvents.ics(EVENT), StandardCharsets.UTF_8))
				.contains("BEGIN:VALARM\r\nACTION:DISPLAY\r\n", "TRIGGER:-PT1H\r\nEND:VALARM\r\n");

		CalendarEvents.Event noReminder = new CalendarEvents.Event("x@bookmyseat", 1, EVENT.stamp(), EVENT.start(),
				Duration.ofHours(3), "Show", null, null, null, null);
		assertThat(new String(CalendarEvents.ics(noReminder), StandardCharsets.UTF_8))
				.doesNotContain("VALARM", "LOCATION", "DESCRIPTION", "URL");
	}

	@Test
	void foldsLongLinesAt75Bytes_withoutSplittingAUtf8Character() {
		String title = "₹".repeat(40) + " a very long show title that goes on and on " + "é".repeat(30);
		StringBuilder out = new StringBuilder();
		CalendarEvents.line(out, "SUMMARY:" + title);

		byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
		String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\r\n");
		assertThat(lines).hasSizeGreaterThan(2);
		assertThat(Arrays.stream(lines).mapToInt(l -> l.getBytes(StandardCharsets.UTF_8).length).max().orElseThrow())
				.isLessThanOrEqualTo(75);
		assertThat(Arrays.stream(lines).skip(1)).allMatch(l -> l.startsWith(" ")); // continuation lines
		// Unfolding gives the line back exactly: no character was cut in half
		assertThat(out.toString().replace("\r\n ", "")).isEqualTo("SUMMARY:" + title + "\r\n");
	}

	@Test
	void shortLinesAreNotFolded() {
		StringBuilder out = new StringBuilder();
		CalendarEvents.line(out, "X".repeat(75));
		assertThat(out.toString()).isEqualTo("X".repeat(75) + "\r\n");
	}
}

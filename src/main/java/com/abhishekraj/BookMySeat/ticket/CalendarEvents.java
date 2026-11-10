package com.abhishekraj.BookMySeat.ticket;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Writes one event as an iCalendar file (RFC 5545), the .ics that Google Calendar, Outlook and Apple Calendar import.
 * Times are in UTC, so no time zone definitions are needed. Text is escaped, and lines longer than 75 bytes are
 * folded without splitting a UTF-8 character. The same uid with a higher sequence updates the event in a calendar
 * that already has it (a rescheduled show).
 */
public final class CalendarEvents {

	public static final String CONTENT_TYPE = "text/calendar; charset=UTF-8";

	private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
	private static final int MAX_LINE_BYTES = 75;

	/** reminder: how long before the start the calendar reminds, or null for none. */
	public record Event(String uid, int sequence, Instant stamp, Instant start, Duration length, String summary,
			String location, String description, String url, Duration reminder) {}

	private CalendarEvents() {}

	public static byte[] ics(Event event) {
		StringBuilder out = new StringBuilder();
		line(out, "BEGIN:VCALENDAR");
		line(out, "VERSION:2.0");
		line(out, "PRODID:-//BookMySeat//Tickets//EN");
		line(out, "CALSCALE:GREGORIAN");
		line(out, "METHOD:PUBLISH");
		line(out, "BEGIN:VEVENT");
		line(out, "UID:" + text(event.uid()));
		line(out, "SEQUENCE:" + event.sequence());
		line(out, "DTSTAMP:" + time(event.stamp()));
		line(out, "DTSTART:" + time(event.start()));
		line(out, "DTEND:" + time(event.start().plus(event.length())));
		line(out, "SUMMARY:" + text(event.summary()));
		if (event.location() != null) {
			line(out, "LOCATION:" + text(event.location()));
		}
		if (event.description() != null) {
			line(out, "DESCRIPTION:" + text(event.description()));
		}
		if (event.url() != null) {
			line(out, "URL:" + event.url()); // a URI value, not TEXT: not escaped
		}
		line(out, "STATUS:CONFIRMED");
		line(out, "TRANSP:OPAQUE");
		if (event.reminder() != null) {
			line(out, "BEGIN:VALARM");
			line(out, "ACTION:DISPLAY");
			line(out, "DESCRIPTION:" + text(event.summary()));
			line(out, "TRIGGER:-" + event.reminder().truncatedTo(ChronoUnit.MINUTES));
			line(out, "END:VALARM");
		}
		line(out, "END:VEVENT");
		line(out, "END:VCALENDAR");
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String time(Instant instant) {
		return UTC.format(instant);
	}

	/** TEXT values: backslash, semicolon and comma are escaped, line breaks become \n. */
	static String text(String value) {
		return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
				.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
	}

	/** Appends a content line ending in CRLF, folded so that no line is longer than 75 bytes. */
	static void line(StringBuilder out, String content) {
		int lineBytes = 0;
		for (int i = 0; i < content.length(); ) {
			int codePoint = content.codePointAt(i);
			int bytes = utf8Length(codePoint);
			if (lineBytes + bytes > MAX_LINE_BYTES) {
				out.append("\r\n ");
				lineBytes = 1; // the leading space of a continuation line counts
			}
			out.appendCodePoint(codePoint);
			lineBytes += bytes;
			i += Character.charCount(codePoint);
		}
		out.append("\r\n");
	}

	private static int utf8Length(int codePoint) {
		return codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
	}
}

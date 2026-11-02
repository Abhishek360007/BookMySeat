package com.abhishekraj.BookMySeat.notification;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.Payment;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.Show;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Renders the emails: every one has a plain-text body and a small HTML body with the same content.
 * Anything that comes from users (names, show titles) is HTML-escaped.
 */
@Component
public class EmailTemplates {

	private static final DateTimeFormatter SHOW_TIME = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH);

	private final EmailProperties properties;

	public EmailTemplates(EmailProperties properties) {
		this.properties = properties;
	}

	public EmailMessage bookingConfirmed(Booking booking) {
		Show show = booking.getShow();
		String seats = seats(booking);
		String subject = "Booking confirmed: " + show.getTitle();
		String text = """
				Hi %s,

				You're booked! Booking #%d
				Show: %s
				When: %s
				Where: %s
				Seats: %s
				Total: %s

				Your bookings: %s
				""".formatted(booking.getUser().getName(), booking.getId(), show.getTitle(), when(show.getStartTime()),
				show.getVenue().getName(), seats, money(booking.getTotalAmount()), bookingsUrl());
		String html = layout("You're booked!", """
				<p>Hi %s,</p>
				<table cellpadding="4" style="border-collapse:collapse">
				<tr><td style="color:#646779">Booking</td><td><b>#%d</b></td></tr>
				<tr><td style="color:#646779">Show</td><td><b>%s</b></td></tr>
				<tr><td style="color:#646779">When</td><td>%s</td></tr>
				<tr><td style="color:#646779">Where</td><td>%s</td></tr>
				<tr><td style="color:#646779">Seats</td><td><b>%s</b></td></tr>
				<tr><td style="color:#646779">Total</td><td>%s</td></tr>
				</table>
				%s""".formatted(esc(booking.getUser().getName()), booking.getId(), esc(show.getTitle()),
				when(show.getStartTime()), esc(show.getVenue().getName()), esc(seats), money(booking.getTotalAmount()),
				button("View my bookings", bookingsUrl())));
		return new EmailMessage(booking.getUser().getEmail(), subject, text, html);
	}

	public EmailMessage bookingCancelled(Booking booking, boolean refundOnItsWay) {
		Show show = booking.getShow();
		String refundNote = refundOnItsWay
				? "Your payment of " + money(booking.getTotalAmount()) + " is being refunded; we'll email you when it's done."
				: "";
		String subject = "Booking cancelled: " + show.getTitle();
		String text = """
				Hi %s,

				Booking #%d is cancelled.
				Show: %s
				When: %s
				Seats: %s
				%s
				""".formatted(booking.getUser().getName(), booking.getId(), show.getTitle(), when(show.getStartTime()),
				seats(booking), refundNote);
		String html = layout("Booking cancelled", """
				<p>Hi %s,</p>
				<p>Booking <b>#%d</b> for <b>%s</b> (%s, seats %s) is cancelled.</p>
				%s""".formatted(esc(booking.getUser().getName()), booking.getId(), esc(show.getTitle()),
				when(show.getStartTime()), esc(seats(booking)), refundNote.isEmpty() ? "" : "<p>" + refundNote + "</p>"));
		return new EmailMessage(booking.getUser().getEmail(), subject, text, html);
	}

	public EmailMessage paymentRefunded(Payment payment) {
		Show show = payment.getShow();
		// No booking: the payment arrived after the seats were gone
		String reason = payment.getBooking() == null
				? "The seats were no longer available when your payment arrived, so it was refunded."
				: "Booking #" + payment.getBooking().getId() + " was cancelled, so its payment was refunded.";
		String subject = "Refund of " + money(payment.getAmount()) + " for " + show.getTitle();
		String text = """
				Hi %s,

				%s
				Amount: %s
				Refund reference: %s

				Banks usually show the money within 5-7 working days.
				""".formatted(payment.getUser().getName(), reason, money(payment.getAmount()), payment.getRefundId());
		String html = layout("Refund on its way", """
				<p>Hi %s,</p>
				<p>%s</p>
				<p>Amount: <b>%s</b><br>Refund reference: %s</p>
				<p style="color:#646779">Banks usually show the money within 5-7 working days.</p>""".formatted(
				esc(payment.getUser().getName()), esc(reason), money(payment.getAmount()), esc(payment.getRefundId())));
		return new EmailMessage(payment.getUser().getEmail(), subject, text, html);
	}

	String layout(String heading, String body) {
		return """
				<!doctype html>
				<html><body style="margin:0;padding:24px;background:#f7f7fb;font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;color:#1c1d26">
				<div style="max-width:520px;margin:0 auto;background:#ffffff;border:1px solid #e3e4ee;border-radius:12px;padding:24px">
				<p style="margin:0 0 16px;font-weight:700;color:#4f46e5">BookMySeat</p>
				<h1 style="font-size:20px;margin:0 0 12px">%s</h1>
				%s
				</div>
				</body></html>
				""".formatted(esc(heading), body);
	}

	String button(String label, String url) {
		return "<p style=\"margin-top:20px\"><a href=\"%s\" style=\"background:#4f46e5;color:#ffffff;padding:10px 18px;border-radius:999px;text-decoration:none;font-weight:600\">%s</a></p>"
				.formatted(esc(url), esc(label));
	}

	String url(String fragment) {
		return properties.publicUrl().replaceAll("/+$", "") + "/#/" + fragment;
	}

	private String bookingsUrl() {
		return url("bookings");
	}

	private String when(Instant time) {
		return SHOW_TIME.format(time.atZone(properties.timeZone())).replace("AM", "am").replace("PM", "pm");
	}

	private static String seats(Booking booking) {
		return booking.getSeats().stream().sorted(Comparator.comparing(Seat::getId))
				.map(Seat::getSeatNumber).collect(Collectors.joining(", "));
	}

	private static String money(BigDecimal amount) {
		return "₹" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	static String esc(String value) {
		return value == null ? "" : HtmlUtils.htmlEscape(value);
	}
}

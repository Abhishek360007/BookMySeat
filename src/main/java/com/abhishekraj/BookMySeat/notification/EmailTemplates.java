package com.abhishekraj.BookMySeat.notification;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.Payment;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.User;
import com.abhishekraj.BookMySeat.entity.WaitlistEntry;
import com.abhishekraj.BookMySeat.ticket.BookingCalendar;
import com.abhishekraj.BookMySeat.ticket.CalendarEvents;
import com.abhishekraj.BookMySeat.ticket.QrCodes;
import com.abhishekraj.BookMySeat.ticket.TicketCodes;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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
	private static final DateTimeFormatter SHOW_TIME_OF_DAY = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

	private final EmailProperties properties;
	private final TicketCodes ticketCodes;
	private final BookingCalendar calendar;

	public EmailTemplates(EmailProperties properties, TicketCodes ticketCodes, BookingCalendar calendar) {
		this.properties = properties;
		this.ticketCodes = ticketCodes;
		this.calendar = calendar;
	}

	public EmailMessage verifyEmail(User user, String token, Duration linkLifetime) {
		// The token travels in the URL fragment (#...), which browsers never send to servers or proxies' logs
		String link = url("verify/" + token);
		String text = """
				Hi %s,

				Please confirm this is your email address by opening this link:
				%s

				The link works once and expires in %d hours. If you didn't create a BookMySeat account, ignore this email.
				""".formatted(user.getName(), link, linkLifetime.toHours());
		String html = layout("Confirm your email", """
				<p>Hi %s,</p>
				<p>Please confirm this is your email address.</p>
				%s
				<p style="color:#646779">The link works once and expires in %d hours. If you didn't create a BookMySeat account, ignore this email.</p>"""
				.formatted(esc(user.getName()), button("Confirm my email", link), linkLifetime.toHours()));
		return new EmailMessage(user.getEmail(), "Confirm your email for BookMySeat", text, html);
	}

	public EmailMessage passwordReset(User user, String token, Duration linkLifetime) {
		String link = url("reset/" + token); // in the URL fragment, like the verification link
		String text = """
				Hi %s,

				Someone (hopefully you) asked to reset the password of your BookMySeat account.
				Choose a new password here:
				%s

				The link works once and expires in %d minutes. If you didn't ask for this, ignore this email: your password stays the same.
				""".formatted(user.getName(), link, linkLifetime.toMinutes());
		String html = layout("Reset your password", """
				<p>Hi %s,</p>
				<p>Someone (hopefully you) asked to reset the password of your BookMySeat account.</p>
				%s
				<p style="color:#646779">The link works once and expires in %d minutes. If you didn't ask for this, ignore this email: your password stays the same.</p>"""
				.formatted(esc(user.getName()), button("Choose a new password", link), linkLifetime.toMinutes()));
		return new EmailMessage(user.getEmail(), "Reset your BookMySeat password", text, html);
	}

	public EmailMessage passwordChanged(User user, Instant when) {
		String text = """
				Hi %s,

				The password of your BookMySeat account was changed on %s, and every device was logged out.

				If this wasn't you, reset your password now: %s
				""".formatted(user.getName(), when(when), url("forgot"));
		String html = layout("Your password was changed", """
				<p>Hi %s,</p>
				<p>The password of your BookMySeat account was changed on %s, and every device was logged out.</p>
				<p>If this wasn't you, reset your password now.</p>
				%s""".formatted(esc(user.getName()), when(when), button("Reset my password", url("forgot"))));
		return new EmailMessage(user.getEmail(), "Your BookMySeat password was changed", text, html);
	}

	public EmailMessage bookingConfirmed(Booking booking) {
		Show show = booking.getShow();
		String seats = seats(booking);
		String ticketCode = ticketCodes.codeFor(booking.getId());
		String subject = "Booking confirmed: " + show.getTitle();
		String text = """
				Hi %s,

				You're booked! Booking #%d
				Show: %s
				When: %s
				Where: %s
				Seats: %s
				Total: %s

				Your ticket is attached: show its QR code at the entrance (or open My bookings on your phone).
				Ticket code: %s
				To put the show in your calendar, open the attached %s.

				Your bookings: %s
				""".formatted(booking.getUser().getName(), booking.getId(), show.getTitle(), when(show.getStartTime()),
				show.getVenue().getName(), seats, total(booking), ticketCode, calendar.filename(booking), bookingsUrl());
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
				<p><b>Your ticket is attached.</b> Show its QR code at the entrance, or open My bookings on your phone.<br>
				<span style="color:#646779">Ticket code: <code>%s</code></span></p>
				<p>To put the show in your calendar, open the attached <b>%s</b>.</p>
				%s""".formatted(esc(booking.getUser().getName()), booking.getId(), esc(show.getTitle()),
				when(show.getStartTime()), esc(show.getVenue().getName()), esc(seats), esc(total(booking)),
				esc(ticketCode), esc(calendar.filename(booking)), button("View my bookings", bookingsUrl())));
		EmailMessage.Attachment ticket = new EmailMessage.Attachment("ticket-" + booking.getId() + ".png", "image/png",
				QrCodes.png(ticketCode, 360));
		return new EmailMessage(booking.getUser().getEmail(), subject, text, html, ticket, calendarEvent(booking));
	}

	/** showCancelled: the venue called off the whole show (not the customer cancelling their booking). */
	public EmailMessage bookingCancelled(Booking booking, boolean refundOnItsWay, boolean showCancelled) {
		Show show = booking.getShow();
		String refundNote = refundOnItsWay
				? "Your payment of " + money(booking.getTotalAmount()) + " is being refunded; we'll email you when it's done."
				: "";
		String what = showCancelled
				? "We're sorry: this show has been cancelled, so booking #" + booking.getId() + " is cancelled too."
				: "Booking #" + booking.getId() + " is cancelled.";
		String subject = (showCancelled ? "Show cancelled: " : "Booking cancelled: ") + show.getTitle();
		String text = """
				Hi %s,

				%s
				Show: %s
				When: %s
				Seats: %s
				%s
				""".formatted(booking.getUser().getName(), what, show.getTitle(), when(show.getStartTime()),
				seats(booking), refundNote);
		String html = layout(showCancelled ? "Show cancelled" : "Booking cancelled", """
				<p>Hi %s,</p>
				<p>%s</p>
				<p><b>%s</b>, %s, seats %s</p>
				%s""".formatted(esc(booking.getUser().getName()), esc(what), esc(show.getTitle()),
				when(show.getStartTime()), esc(seats(booking)), refundNote.isEmpty() ? "" : "<p>" + refundNote + "</p>"));
		return new EmailMessage(booking.getUser().getEmail(), subject, text, html);
	}

	public EmailMessage showRescheduled(Booking booking, Instant previousStart) {
		Show show = booking.getShow();
		String subject = "New time for " + show.getTitle() + ": " + when(show.getStartTime());
		String text = """
				Hi %s,

				%s has moved to a new time. Your booking #%d and seats stay the same.
				Was: %s
				Now: %s
				Where: %s
				Seats: %s

				The attached %s moves the show to the new time in your calendar.
				If the new time doesn't work for you, you can cancel until 2 hours before the show (online payments are refunded in full): %s
				""".formatted(booking.getUser().getName(), show.getTitle(), booking.getId(), when(previousStart),
				when(show.getStartTime()), show.getVenue().getName(), seats(booking), calendar.filename(booking), bookingsUrl());
		String html = layout("New show time", """
				<p>Hi %s,</p>
				<p><b>%s</b> has moved to a new time. Your booking <b>#%d</b> and seats stay the same.</p>
				<table cellpadding="4" style="border-collapse:collapse">
				<tr><td style="color:#646779">Was</td><td><s>%s</s></td></tr>
				<tr><td style="color:#646779">Now</td><td><b>%s</b></td></tr>
				<tr><td style="color:#646779">Where</td><td>%s</td></tr>
				<tr><td style="color:#646779">Seats</td><td>%s</td></tr>
				</table>
				<p>The attached <b>%s</b> moves the show to the new time in your calendar.</p>
				<p>If the new time doesn't work for you, you can cancel until 2 hours before the show (online payments are refunded in full).</p>
				%s""".formatted(esc(booking.getUser().getName()), esc(show.getTitle()), booking.getId(),
				when(previousStart), when(show.getStartTime()), esc(show.getVenue().getName()), esc(seats(booking)),
				esc(calendar.filename(booking)), button("View my bookings", bookingsUrl())));
		return new EmailMessage(booking.getUser().getEmail(), subject, text, html, calendarEvent(booking));
	}

	/** After the show: one tap to rate it. The link opens the review page of the web app. */
	public EmailMessage reviewRequest(Booking booking) {
		Show show = booking.getShow();
		String link = url("review/" + show.getId());
		String subject = "How was " + show.getTitle() + "?";
		String text = """
				Hi %s,

				We hope you enjoyed %s at %s on %s.
				How was it? Rate it from 1 to 5 stars and, if you like, say a few words for the next people deciding:
				%s

				It takes ten seconds, and you can change it later.
				""".formatted(booking.getUser().getName(), show.getTitle(), show.getVenue().getName(),
				when(show.getStartTime()), link);
		String html = layout("How was it?", """
				<p>Hi %s,</p>
				<p>We hope you enjoyed <b>%s</b> at %s on %s.</p>
				<p>Rate it from 1 to 5 stars and, if you like, say a few words for the next people deciding.</p>
				%s
				<p style="color:#646779">It takes ten seconds, and you can change it later.</p>""".formatted(
				esc(booking.getUser().getName()), esc(show.getTitle()), esc(show.getVenue().getName()),
				when(show.getStartTime()), button("Rate " + show.getTitle(), link)));
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

	/** Seats came free for a sold-out show and are held for this person until the offer expires. */
	public EmailMessage waitlistOffer(WaitlistEntry entry) {
		Show show = entry.getShow();
		User user = entry.getUser();
		String seats = entry.getOfferedSeats().stream().sorted(Comparator.comparing(Seat::getId))
				.map(Seat::getSeatNumber).collect(Collectors.joining(", "));
		String until = SHOW_TIME_OF_DAY.format(entry.getOfferExpiresAt().atZone(properties.timeZone()))
				.replace("AM", "am").replace("PM", "pm");
		String link = url("show/" + show.getId());
		String subject = "Seats for you: " + show.getTitle();
		String text = """
				Hi %s,

				Good news: seats came free for %s (%s), and you're next on the waitlist.
				Seats %s are held for you until %s. Book them before then, or they go to the next person in line.

				Book your seats: %s
				""".formatted(user.getName(), show.getTitle(), when(show.getStartTime()), seats, until, link);
		String html = layout("Seats are waiting for you", """
				<p>Hi %s,</p>
				<p>Good news: seats came free for <b>%s</b> (%s), and you're next on the waitlist.</p>
				<p>Seats <b>%s</b> are held for you until <b>%s</b>. Book them before then, or they go to the next person in line.</p>
				%s""".formatted(esc(user.getName()), esc(show.getTitle()), when(show.getStartTime()), esc(seats), until,
				button("Book my seats", link)));
		return new EmailMessage(user.getEmail(), subject, text, html);
	}

	/** The show was cancelled while this person was on its waitlist. */
	public EmailMessage waitlistClosed(WaitlistEntry entry) {
		Show show = entry.getShow();
		User user = entry.getUser();
		String subject = "Show cancelled: " + show.getTitle();
		String text = """
				Hi %s,

				We're sorry: %s (%s) has been cancelled, so its waitlist is closed. You don't need to do anything.

				Other shows: %s
				""".formatted(user.getName(), show.getTitle(), when(show.getStartTime()), url(""));
		String html = layout("Show cancelled", """
				<p>Hi %s,</p>
				<p>We're sorry: <b>%s</b> (%s) has been cancelled, so its waitlist is closed. You don't need to do anything.</p>
				%s""".formatted(esc(user.getName()), esc(show.getTitle()), when(show.getStartTime()),
				button("Find another show", url(""))));
		return new EmailMessage(user.getEmail(), subject, text, html);
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

	private EmailMessage.Attachment calendarEvent(Booking booking) {
		return new EmailMessage.Attachment(calendar.filename(booking), CalendarEvents.CONTENT_TYPE, calendar.ics(booking));
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

	/** "₹360.00", or "₹360.00 (₹40.00 off with TENOFF)" when a promo code was used. */
	private static String total(Booking booking) {
		String total = money(booking.getTotalAmount());
		if (booking.getPromoCode() == null || booking.getDiscountAmount().signum() == 0) {
			return total;
		}
		return total + " (" + money(booking.getDiscountAmount()) + " off with " + booking.getPromoCode() + ")";
	}

	private static String money(BigDecimal amount) {
		return "₹" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	static String esc(String value) {
		return value == null ? "" : HtmlUtils.htmlEscape(value);
	}
}

package com.abhishekraj.BookMySeat.notification;

import java.util.List;

/**
 * One email, already rendered: a plain-text body for every client and an HTML body for those that show it,
 * plus its attachments (e.g. the e-ticket and a calendar event), in order; none is an empty list.
 */
public record EmailMessage(String to, String subject, String text, String html, List<Attachment> attachments) {

	public EmailMessage {
		attachments = attachments == null ? List.of() : List.copyOf(attachments);
	}

	public EmailMessage(String to, String subject, String text, String html) {
		this(to, subject, text, html, List.of());
	}

	public EmailMessage(String to, String subject, String text, String html, Attachment... attachments) {
		this(to, subject, text, html, List.of(attachments));
	}

	public record Attachment(String filename, String contentType, byte[] content) {}
}

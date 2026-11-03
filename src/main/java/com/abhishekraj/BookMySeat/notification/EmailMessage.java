package com.abhishekraj.BookMySeat.notification;

/**
 * One email, already rendered: a plain-text body for every client and an HTML body for those that show it,
 * plus at most one attachment (e.g. the e-ticket), or null.
 */
public record EmailMessage(String to, String subject, String text, String html, Attachment attachment) {

	public EmailMessage(String to, String subject, String text, String html) {
		this(to, subject, text, html, null);
	}

	public record Attachment(String filename, String contentType, byte[] content) {}
}

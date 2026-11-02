package com.abhishekraj.BookMySeat.notification;

/** Delivers one email or throws; the outbox retries failures later. */
public interface EmailSender {

	void send(EmailMessage message);
}

package com.abhishekraj.BookMySeat.notification;

import lombok.extern.slf4j.Slf4j;

// No mail server configured: the email is only logged (recipient and subject, not the body, which may hold links with tokens)
@Slf4j
public class LoggingEmailSender implements EmailSender {

	@Override
	public void send(EmailMessage message) {
		log.info("Email (not sent, email.provider=log) to {}: {}", message.to(), message.subject());
	}
}

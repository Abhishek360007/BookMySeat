package com.abhishekraj.BookMySeat.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

// Plain SMTP via spring.mail.* (Mailpit in development)
public class SmtpEmailSender implements EmailSender {

	private final JavaMailSender mailSender;
	private final EmailProperties properties;

	public SmtpEmailSender(JavaMailSender mailSender, EmailProperties properties) {
		this.mailSender = mailSender;
		this.properties = properties;
	}

	@Override
	public void send(EmailMessage message) {
		MimeMessage mime = mailSender.createMimeMessage();
		try {
			MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
			helper.setFrom(properties.from(), properties.fromName());
			helper.setTo(message.to());
			helper.setSubject(message.subject());
			helper.setText(message.text(), message.html()); // multipart/alternative: text and HTML
			if (message.attachment() != null) {
				helper.addAttachment(message.attachment().filename(),
						new ByteArrayResource(message.attachment().content()), message.attachment().contentType());
			}
		} catch (MessagingException | UnsupportedEncodingException ex) {
			throw new MailSendException("Could not build the email", ex);
		}
		mailSender.send(mime);
	}
}

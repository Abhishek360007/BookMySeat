package com.abhishekraj.BookMySeat.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One attachment of an outbox email, stored with it so a retry sends exactly what was queued. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxAttachment {

	@Column(nullable = false, length = 100)
	private String filename;

	@Column(nullable = false, length = 100)
	private String contentType;

	@Column(nullable = false)
	private byte[] content;

	OutboxAttachment(EmailMessage.Attachment attachment) {
		this.filename = attachment.filename();
		this.contentType = attachment.contentType();
		this.content = attachment.content();
	}

	EmailMessage.Attachment toAttachment() {
		return new EmailMessage.Attachment(filename, contentType, content);
	}
}

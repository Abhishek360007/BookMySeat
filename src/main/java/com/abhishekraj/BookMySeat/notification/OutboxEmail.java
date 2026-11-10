package com.abhishekraj.BookMySeat.notification;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "email_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEmail {

	public enum Status { PENDING, SENT, FAILED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 40)
	private String kind;

	@Column(nullable = false)
	private String recipient;

	@Column(nullable = false)
	private String subject;

	@Column(name = "body_text", nullable = false, columnDefinition = "text")
	private String bodyText;

	@Column(name = "body_html", nullable = false, columnDefinition = "text")
	private String bodyHtml;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(nullable = false)
	private int attempts;

	@Column(nullable = false)
	private Instant nextAttemptAt;

	@Column(length = 500)
	private String lastError;

	// Eager: an email is only ever loaded to be sent (or looked at), and then it needs its attachments
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "email_outbox_attachment", joinColumns = @JoinColumn(name = "email_id"))
	@OrderColumn(name = "idx")
	private List<OutboxAttachment> attachments = new ArrayList<>();

	@Column(nullable = false)
	private Instant createdAt;

	private Instant sentAt;

	public OutboxEmail(String kind, EmailMessage message, Instant now) {
		this.kind = kind;
		this.recipient = message.to();
		this.subject = message.subject();
		this.bodyText = message.text();
		this.bodyHtml = message.html();
		message.attachments().forEach(attachment -> this.attachments.add(new OutboxAttachment(attachment)));
		this.status = Status.PENDING;
		this.nextAttemptAt = now;
		this.createdAt = now;
	}

	public EmailMessage toMessage() {
		return new EmailMessage(recipient, subject, bodyText, bodyHtml,
				attachments.stream().map(OutboxAttachment::toAttachment).toList());
	}

	public boolean isDue(Instant now) {
		return status == Status.PENDING && !nextAttemptAt.isAfter(now);
	}

	public void sent(Instant now) {
		attempts++;
		status = Status.SENT;
		sentAt = now;
		lastError = null;
	}

	/** Retries after firstRetryDelay, then twice as long each time (capped at an hour); gives up after maxAttempts. */
	public void failed(String error, Instant now, int maxAttempts, Duration firstRetryDelay) {
		attempts++;
		lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
		if (attempts >= maxAttempts) {
			status = Status.FAILED;
			return;
		}
		Duration delay = firstRetryDelay.multipliedBy(1L << Math.min(attempts - 1, 20));
		nextAttemptAt = now.plus(delay.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : delay);
	}
}

package com.abhishekraj.BookMySeat.notification;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

// Stands in for the email provider in tests: records what was sent, and can fail or be slow on purpose
public class FakeEmailSender implements EmailSender {

	public final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
	public final AtomicInteger calls = new AtomicInteger();
	public volatile boolean fail = false;
	public volatile Duration delay = Duration.ZERO;

	@Override
	public void send(EmailMessage message) {
		calls.incrementAndGet();
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (fail) {
			throw new IllegalStateException("Email provider is down (simulated)");
		}
		sent.add(message);
	}

	public List<EmailMessage> sentTo(String recipient) {
		return sent.stream().filter(m -> m.to().equals(recipient)).toList();
	}

	public void reset() {
		sent.clear();
		calls.set(0);
		fail = false;
		delay = Duration.ZERO;
	}
}

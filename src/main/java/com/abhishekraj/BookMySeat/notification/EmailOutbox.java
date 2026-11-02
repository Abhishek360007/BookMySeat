package com.abhishekraj.BookMySeat.notification;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Queues emails. Never sends inside the caller's transaction: if the transaction rolls back, the email is gone
 * with it; if it commits, the email is stored and will be delivered even if the app crashes right after.
 */
@Slf4j
@Service
public class EmailOutbox {

	private final OutboxEmailRepository repository;
	private final OutboxDispatcher dispatcher;
	private final EmailProperties properties;
	private final Clock clock;
	// One background sender for "send right after commit", so request threads never wait on the email provider
	private final ExecutorService immediateSender = Executors.newSingleThreadExecutor(
			Thread.ofVirtual().name("email-outbox").factory());

	public EmailOutbox(OutboxEmailRepository repository, OutboxDispatcher dispatcher, EmailProperties properties, Clock clock) {
		this.repository = repository;
		this.dispatcher = dispatcher;
		this.properties = properties;
		this.clock = clock;
	}

	/** MANDATORY: an outbox row only makes sense as part of the transaction that made the change. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueue(String kind, EmailMessage message) {
		OutboxEmail email = repository.save(new OutboxEmail(kind, message, Instant.now(clock)));
		if (!properties.outbox().sendImmediately()) {
			return;
		}
		Long id = email.getId();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				// If this fails or the app stops first, the scheduled job delivers it instead
				immediateSender.execute(() -> dispatcher.deliverQuietly(id));
			}
		});
	}

	@PreDestroy
	void shutdown() {
		immediateSender.shutdown();
	}
}

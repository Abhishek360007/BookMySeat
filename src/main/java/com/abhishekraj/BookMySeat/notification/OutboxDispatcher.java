package com.abhishekraj.BookMySeat.notification;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Delivers outbox emails. Each email is sent in its own transaction that holds its row lock while sending, so
 * two senders (the job and an after-commit send) never deliver the same email. Delivery is at least once:
 * if the app dies after the provider accepted an email but before the commit, it is sent again later.
 */
@Slf4j
@Service
public class OutboxDispatcher {

	private static final int BATCH = 20;

	private final OutboxEmailRepository repository;
	private final EmailSender sender;
	private final EmailProperties properties;
	private final MeterRegistry meterRegistry;
	private final Clock clock;
	private final OutboxDispatcher self;

	// self: calls through the Spring proxy, so deliver() really runs in its own transaction
	public OutboxDispatcher(OutboxEmailRepository repository, EmailSender sender, EmailProperties properties,
			MeterRegistry meterRegistry, Clock clock, @Lazy OutboxDispatcher self) {
		this.repository = repository;
		this.sender = sender;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
		this.clock = clock;
		this.self = self;
	}

	/** Delivers emails that are due, a batch at a time. Returns how many were handled (sent or failed). */
	public int deliverDue() {
		int handled = 0;
		for (Long id : repository.findDueIds(Instant.now(clock), BATCH)) {
			if (deliverQuietly(id)) {
				handled++;
			}
		}
		return handled;
	}

	public boolean deliverQuietly(Long id) {
		try {
			return self.deliver(id);
		} catch (RuntimeException ex) {
			log.warn("Could not process outbox email {}: {}", id, ex.getMessage());
			return false;
		}
	}

	/** Returns false when there was nothing to do: someone else holds it, or it is no longer due. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean deliver(Long id) {
		OutboxEmail email = repository.lockIfFree(id).orElse(null);
		Instant now = Instant.now(clock);
		if (email == null || !email.isDue(now)) {
			return false;
		}
		try {
			sender.send(email.toMessage());
			email.sent(Instant.now(clock));
			meterRegistry.counter("emails", "kind", email.getKind(), "outcome", "sent").increment();
		} catch (RuntimeException ex) {
			email.failed(ex.getClass().getSimpleName() + ": " + ex.getMessage(), now,
					properties.outbox().maxAttempts(), properties.outbox().firstRetryDelay());
			boolean gaveUp = email.getStatus() == OutboxEmail.Status.FAILED;
			meterRegistry.counter("emails", "kind", email.getKind(), "outcome", gaveUp ? "failed" : "retry").increment();
			if (gaveUp) {
				log.error("Giving up on {} email {} after {} attempts: {}", email.getKind(), id, email.getAttempts(), ex.getMessage());
			} else {
				log.warn("{} email {} failed (attempt {}), retrying at {}: {}", email.getKind(), id, email.getAttempts(),
						email.getNextAttemptAt(), ex.getMessage());
			}
		}
		return true;
	}

	@Transactional
	public int purgeSent() {
		return repository.deleteSentBefore(Instant.now(clock).minus(properties.outbox().keepSent()));
	}
}

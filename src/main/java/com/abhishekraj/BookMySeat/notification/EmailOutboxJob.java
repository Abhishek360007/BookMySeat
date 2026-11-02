package com.abhishekraj.BookMySeat.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Delivers what the after-commit send missed (provider down, app restarted) and retries failures with backoff
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailOutboxJob {

	private final OutboxDispatcher dispatcher;

	@Scheduled(initialDelayString = "${email.outbox.initial-delay}", fixedDelayString = "${email.outbox.interval}")
	@SchedulerLock(name = "emailOutbox", lockAtLeastFor = "PT20S")
	public void run() {
		int handled = dispatcher.deliverDue();
		int purged = dispatcher.purgeSent();
		if (handled > 0 || purged > 0) {
			log.info("Email outbox: {} delivered or retried, {} old sent emails removed", handled, purged);
		}
	}
}

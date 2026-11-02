package com.abhishekraj.BookMySeat.notification;

import com.abhishekraj.BookMySeat.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The outbox itself: stored with the transaction, delivered by the dispatcher, retried with backoff, never twice
@SpringBootTest(properties = {
		"email.outbox.send-immediately=false", // these tests drive delivery themselves
		"email.outbox.max-attempts=3",
		"email.outbox.first-retry-delay=PT30S",
		"email.outbox.initial-delay=PT1H", // keep the scheduled job out of the way
		"email.suppressed-recipients=demo@example.com,nobody@example.com"
})
@Import({TestcontainersConfig.class, EmailOutboxTest.FakeEmail.class})
class EmailOutboxTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeEmail {
		@Bean
		@Primary
		FakeEmailSender fakeEmailSender() {
			return new FakeEmailSender();
		}
	}

	static final EmailMessage MESSAGE = new EmailMessage("guest@example.com", "Hello", "Hi there", "<p>Hi there</p>");

	@Autowired EmailOutbox outbox;
	@Autowired OutboxDispatcher dispatcher;
	@Autowired OutboxEmailRepository repository;
	@Autowired FakeEmailSender sender;
	@Autowired TransactionTemplate tx;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		sender.reset();
	}

	@AfterEach
	void cleanUp() {
		repository.deleteAll();
	}

	@Test
	void enqueue_outsideATransaction_isRefused() {
		assertThatThrownBy(() -> outbox.enqueue("TEST", MESSAGE)).isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void rolledBackTransaction_leavesNoEmail() {
		tx.executeWithoutResult(status -> {
			outbox.enqueue("TEST", MESSAGE);
			status.setRollbackOnly(); // e.g. the booking failed after the email was queued
		});

		assertThat(repository.count()).isZero();
		dispatcher.deliverDue();
		assertThat(sender.sent).isEmpty();
	}

	@Test
	void committedEmail_isDeliveredOnce_andMarkedSent() {
		tx.executeWithoutResult(status -> outbox.enqueue("TEST", MESSAGE));

		assertThat(dispatcher.deliverDue()).isEqualTo(1);
		assertThat(dispatcher.deliverDue()).isZero(); // nothing left to do

		assertThat(sender.sent).containsExactly(MESSAGE);
		assertThat(repository.findAll()).singleElement().satisfies(e -> {
			assertThat(e.getStatus()).isEqualTo(OutboxEmail.Status.SENT);
			assertThat(e.getAttempts()).isEqualTo(1);
			assertThat(e.getSentAt()).isNotNull();
		});
	}

	@Test
	void failure_isRetriedWithBackoff_untilItSucceeds() {
		tx.executeWithoutResult(status -> outbox.enqueue("TEST", MESSAGE));
		sender.fail = true;

		Instant before = Instant.now();
		dispatcher.deliverDue();
		OutboxEmail afterFirst = repository.findAll().getFirst();
		assertThat(afterFirst.getStatus()).isEqualTo(OutboxEmail.Status.PENDING);
		assertThat(afterFirst.getAttempts()).isEqualTo(1);
		assertThat(afterFirst.getLastError()).contains("Email provider is down");
		assertThat(afterFirst.getNextAttemptAt()).isBetween(before.plusSeconds(29), Instant.now().plusSeconds(31));

		// Not due yet: the next run leaves it alone
		dispatcher.deliverDue();
		assertThat(sender.calls).hasValue(1);

		makeDue();
		dispatcher.deliverDue();
		OutboxEmail afterSecond = repository.findAll().getFirst();
		assertThat(afterSecond.getAttempts()).isEqualTo(2);
		// Twice the first delay this time
		assertThat(Duration.between(Instant.now(), afterSecond.getNextAttemptAt())).isBetween(Duration.ofSeconds(55), Duration.ofSeconds(61));

		sender.fail = false;
		makeDue();
		dispatcher.deliverDue();
		assertThat(sender.sent).containsExactly(MESSAGE);
		assertThat(repository.findAll().getFirst().getStatus()).isEqualTo(OutboxEmail.Status.SENT);
	}

	@Test
	void tooManyFailures_marksTheEmailFailed_andStopsTrying() {
		tx.executeWithoutResult(status -> outbox.enqueue("TEST", MESSAGE));
		sender.fail = true;

		for (int i = 0; i < 5; i++) {
			makeDue();
			dispatcher.deliverDue();
		}

		assertThat(sender.calls).hasValue(3); // max-attempts=3
		assertThat(repository.findAll()).singleElement().satisfies(e -> {
			assertThat(e.getStatus()).isEqualTo(OutboxEmail.Status.FAILED);
			assertThat(e.getAttempts()).isEqualTo(3);
		});
	}

	@Test
	void twoSendersAtOnce_deliverEachEmailExactlyOnce() throws Exception {
		for (int i = 0; i < 6; i++) {
			EmailMessage message = new EmailMessage("guest" + i + "@example.com", "Hello " + i, "Hi", "<p>Hi</p>");
			tx.executeWithoutResult(status -> outbox.enqueue("TEST", message));
		}
		sender.delay = Duration.ofMillis(200); // a slow provider widens the race window

		ExecutorService pool = Executors.newFixedThreadPool(3);
		CountDownLatch start = new CountDownLatch(1);
		Callable<Integer> run = () -> {
			start.await();
			return dispatcher.deliverDue();
		};
		Future<Integer> a = pool.submit(run);
		Future<Integer> b = pool.submit(run);
		Future<Integer> c = pool.submit(run);
		start.countDown();
		int handled = a.get(30, TimeUnit.SECONDS) + b.get(30, TimeUnit.SECONDS) + c.get(30, TimeUnit.SECONDS);
		pool.shutdown();

		assertThat(handled).isEqualTo(6);
		assertThat(sender.calls).hasValue(6);
		assertThat(sender.sent).extracting(EmailMessage::to).doesNotHaveDuplicates().hasSize(6);
	}

	@Test
	void suppressedRecipient_isNeverQueued() {
		tx.executeWithoutResult(status -> outbox.enqueue("TEST", new EmailMessage("Demo@Example.com", "Hi", "x", "<p>x</p>")));

		assertThat(repository.count()).isZero();
	}

	@Test
	void purge_removesOldSentEmails_only() {
		tx.executeWithoutResult(status -> {
			outbox.enqueue("TEST", MESSAGE);
			outbox.enqueue("TEST", new EmailMessage("other@example.com", "Later", "x", "<p>x</p>"));
		});
		Long first = repository.findAll().getFirst().getId();
		dispatcher.deliver(first);
		jdbc.update("UPDATE email_outbox SET sent_at = now() - interval '8 days' WHERE id = ?", first);

		assertThat(dispatcher.purgeSent()).isEqualTo(1);
		assertThat(repository.findAll()).singleElement()
				.satisfies(e -> assertThat(e.getStatus()).isEqualTo(OutboxEmail.Status.PENDING));
	}

	private void makeDue() {
		jdbc.update("UPDATE email_outbox SET next_attempt_at = now() - interval '1 second' WHERE status = 'PENDING'");
	}
}

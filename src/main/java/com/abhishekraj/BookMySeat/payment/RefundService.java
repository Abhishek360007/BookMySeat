package com.abhishekraj.BookMySeat.payment;

import com.abhishekraj.BookMySeat.entity.Payment;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;
import com.abhishekraj.BookMySeat.repository.PaymentRepository;
import com.abhishekraj.BookMySeat.support.AfterCommit;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * Sends money back. A refund is first recorded as REFUND_PENDING in the same transaction as the decision,
 * then requested from Razorpay after that commit; if Razorpay fails, the retry job picks it up later.
 */
@Slf4j
@Service
public class RefundService {

	private final PaymentRepository paymentRepository;
	private final PaymentGateway gateway;
	private final Clock clock;
	private final ApplicationEventPublisher events;
	private final MeterRegistry meterRegistry;
	private final RefundService self;

	// self: calls through the Spring proxy, so refund() really runs in its own transaction
	public RefundService(PaymentRepository paymentRepository, PaymentGateway gateway, Clock clock,
			ApplicationEventPublisher events, MeterRegistry meterRegistry, @Lazy RefundService self) {
		this.paymentRepository = paymentRepository;
		this.gateway = gateway;
		this.clock = clock;
		this.events = events;
		this.meterRegistry = meterRegistry;
		this.self = self;
		for (String outcome : new String[] {"refunded", "retry", "manual"}) {
			meterRegistry.counter("refunds", "outcome", outcome); // at 0 from startup, see BookingMetrics
		}
	}

	/** Requests the refund once the current transaction (which set REFUND_PENDING) has committed. */
	public void refundAfterCommit(String orderId) {
		AfterCommit.run(() -> refundQuietly(orderId));
	}

	public void refundExtraPaymentAfterCommit(String paymentId, BigDecimal amount) {
		AfterCommit.run(() -> {
			try {
				gateway.refund(paymentId, PaymentService.toMinor(amount));
				count("refunded");
			} catch (RuntimeException ex) {
				count("manual"); // nothing retries this one
				log.error("Refund of extra payment {} failed; refund it from the Razorpay dashboard", paymentId, ex);
			}
		});
	}

	/**
	 * Refunds a REFUND_PENDING payment; safe to call repeatedly (used by the retry job too).
	 * REQUIRES_NEW: when called from afterCommit, a plain @Transactional would join the transaction that just
	 * committed and its changes would never be saved.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void refund(String orderId) {
		Payment payment = paymentRepository.findForUpdateByOrderId(orderId).orElseThrow();
		if (payment.getStatus() != PaymentStatus.REFUND_PENDING) {
			return;
		}
		String refundId = gateway.refund(payment.getPaymentId(), PaymentService.toMinor(payment.getAmount()));
		payment.refunded(refundId, Instant.now(clock));
		events.publishEvent(new PaymentRefundedEvent(orderId)); // e.g. the refund email, committed with this change
		AfterCommit.run(() -> count("refunded"));
		log.info("Order {} refunded: {}", orderId, refundId);
	}

	public void refundQuietly(String orderId) {
		try {
			self.refund(orderId);
		} catch (RuntimeException ex) {
			count("retry");
			log.warn("Refund for order {} failed, will retry: {}", orderId, ex.getMessage());
		}
	}

	private void count(String outcome) {
		meterRegistry.counter("refunds", "outcome", outcome).increment();
	}
}

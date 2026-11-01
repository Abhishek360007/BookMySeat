package com.abhishekraj.BookMySeat.payment;

import com.abhishekraj.BookMySeat.entity.Payment;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;
import com.abhishekraj.BookMySeat.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
	private final RefundService self;

	// self: calls through the Spring proxy, so refund() really runs in its own transaction
	public RefundService(PaymentRepository paymentRepository, PaymentGateway gateway, Clock clock, @Lazy RefundService self) {
		this.paymentRepository = paymentRepository;
		this.gateway = gateway;
		this.clock = clock;
		this.self = self;
	}

	/** Requests the refund once the current transaction (which set REFUND_PENDING) has committed. */
	public void refundAfterCommit(String orderId) {
		afterCommit(() -> refundQuietly(orderId));
	}

	public void refundExtraPaymentAfterCommit(String paymentId, BigDecimal amount) {
		afterCommit(() -> {
			try {
				gateway.refund(paymentId, PaymentService.toMinor(amount));
			} catch (RuntimeException ex) {
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
		log.info("Order {} refunded: {}", orderId, refundId);
	}

	public void refundQuietly(String orderId) {
		try {
			self.refund(orderId);
		} catch (RuntimeException ex) {
			log.warn("Refund for order {} failed, will retry: {}", orderId, ex.getMessage());
		}
	}

	private static void afterCommit(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		} else {
			action.run();
		}
	}
}

package com.abhishekraj.BookMySeat.payment;

import com.abhishekraj.BookMySeat.entity.Payment;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;
import com.abhishekraj.BookMySeat.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

// Expires unpaid checkouts (releasing their seats) and retries refunds that Razorpay rejected earlier.
// Each payment is handled in its own transaction, so one failure doesn't block the rest.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentMaintenanceJob {

	private final PaymentRepository paymentRepository;
	private final PaymentConfirmationService confirmation;
	private final RefundService refundService;
	private final PaymentProperties properties;
	private final Clock clock;

	@Scheduled(initialDelayString = "${payments.maintenance.initial-delay}", fixedDelayString = "${payments.maintenance.interval}")
	@SchedulerLock(name = "paymentMaintenance", lockAtLeastFor = "PT20S")
	public void run() {
		if (!properties.enabled()) {
			return;
		}
		for (Payment payment : paymentRepository.findTop50ByStatusAndExpiresAtBefore(PaymentStatus.PENDING, Instant.now(clock))) {
			confirmation.expireIfUnpaid(payment.getOrderId());
		}
		for (Payment payment : paymentRepository.findTop50ByStatus(PaymentStatus.REFUND_PENDING)) {
			refundService.refundQuietly(payment.getOrderId());
		}
	}
}

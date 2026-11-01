package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Payment;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	// Row lock: the client callback and the webhook can report the same payment at the same moment;
	// whichever takes the lock first decides, the other sees the result
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findForUpdateByOrderId(String orderId);

	Optional<Payment> findByOrderIdAndUserId(String orderId, Long userId);

	Optional<Payment> findFirstByUserIdAndShowIdAndStatusOrderByCreatedAtDesc(Long userId, Long showId, PaymentStatus status);

	boolean existsByOrderId(String orderId);

	Optional<Payment> findByBookingId(Long bookingId);

	List<Payment> findTop50ByStatusAndExpiresAtBefore(PaymentStatus status, Instant now);

	List<Payment> findTop50ByStatus(PaymentStatus status);
}

package com.abhishekraj.BookMySeat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

// One checkout = one Razorpay order. State changes only through the methods below.
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_id")
	private Show show;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "payment_seats", joinColumns = @JoinColumn(name = "payment_id"))
	@Column(name = "seat_id")
	private Set<Long> seatIds = new HashSet<>();

	@Column(name = "order_id", nullable = false, length = 64)
	private String orderId;

	@Column(name = "payment_id", length = 64)
	private String paymentId;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentStatus status;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "booking_id")
	private Booking booking;

	@Column(name = "refund_id", length = 64)
	private String refundId;

	@Column(name = "failure_reason")
	private String failureReason;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant expiresAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	public Payment(User user, Show show, Set<Long> seatIds, String orderId, BigDecimal amount, String currency,
			Instant now, Instant expiresAt) {
		this.user = user;
		this.show = show;
		this.seatIds = new HashSet<>(seatIds);
		this.orderId = orderId;
		this.amount = amount;
		this.currency = currency;
		this.status = PaymentStatus.PENDING;
		this.createdAt = now;
		this.expiresAt = expiresAt;
		this.updatedAt = now;
	}

	public boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt);
	}

	public void confirm(String paymentId, Booking booking, Instant now) {
		this.paymentId = paymentId;
		this.booking = booking;
		this.failureReason = null; // an earlier failed attempt on this order no longer matters
		this.status = PaymentStatus.CONFIRMED;
		this.updatedAt = now;
	}

	public void expire(Instant now) {
		this.status = PaymentStatus.EXPIRED;
		this.updatedAt = now;
	}

	/** Money was (or will be) taken but there is no booking to keep: it must go back to the customer. */
	public void requireRefund(String paymentId, String reason, Instant now) {
		if (this.paymentId == null) {
			this.paymentId = paymentId;
		}
		this.failureReason = reason;
		this.status = PaymentStatus.REFUND_PENDING;
		this.updatedAt = now;
	}

	public void refunded(String refundId, Instant now) {
		this.refundId = refundId;
		this.status = PaymentStatus.REFUNDED;
		this.updatedAt = now;
	}

	public void recordFailure(String reason, Instant now) {
		this.failureReason = reason;
		this.updatedAt = now;
	}
}

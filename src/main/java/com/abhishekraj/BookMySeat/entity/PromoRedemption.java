package com.abhishekraj.BookMySeat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// One use of a promo code by one person: for a booking confirmed directly, or for a Razorpay order (RESERVED until
// it is paid).
@Entity
@Table(name = "promo_redemptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PromoRedemption {

	public enum Status {
		/** Held for a payment in progress; counts towards the limits. */
		RESERVED,
		/** A booking has it. */
		REDEEMED,
		/** Given back: the booking was cancelled, or the payment expired or was refunded. */
		RELEASED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "promo_code_id")
	private PromoCode promoCode;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private User user;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "booking_id")
	private Booking booking;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "payment_id")
	private Payment payment;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant releasedAt;

	/** Used by a booking confirmed directly. */
	public PromoRedemption(PromoCode promoCode, User user, Booking booking, Instant now) {
		this.promoCode = promoCode;
		this.user = user;
		this.booking = booking;
		this.status = Status.REDEEMED;
		this.createdAt = now;
	}

	/** Reserved for a payment in progress. */
	public PromoRedemption(PromoCode promoCode, User user, Payment payment, Instant now) {
		this.promoCode = promoCode;
		this.user = user;
		this.payment = payment;
		this.status = Status.RESERVED;
		this.createdAt = now;
	}

	public void redeem(Booking booking) {
		this.booking = booking;
		this.status = Status.REDEEMED;
	}

	/** A payment that expired (use released) arrived late after all and took the use again. */
	public void reserveAgain() {
		this.status = Status.RESERVED;
		this.releasedAt = null;
	}

	public void release(Instant now) {
		this.status = Status.RELEASED;
		this.releasedAt = now;
	}
}

package com.abhishekraj.BookMySeat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// One use of a promo code by one person.
@Entity
@Table(name = "promo_redemptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PromoRedemption {

	public enum Status { REDEEMED, RELEASED }

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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant releasedAt;

	public PromoRedemption(PromoCode promoCode, User user, Booking booking, Instant now) {
		this.promoCode = promoCode;
		this.user = user;
		this.booking = booking;
		this.status = Status.REDEEMED;
		this.createdAt = now;
	}

	public void release(Instant now) {
		this.status = Status.RELEASED;
		this.releasedAt = now;
	}
}

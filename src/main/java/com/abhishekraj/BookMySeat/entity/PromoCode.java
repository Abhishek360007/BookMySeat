package com.abhishekraj.BookMySeat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

// A discount code made by an admin. The rules that need nothing but the code itself live here; the per-person
// limit needs the redemptions and is checked by PromoCodeService.
@Entity
@Table(name = "promo_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PromoCode {

	/** No discount brings a total below this: Razorpay can't take a payment under ₹1. */
	public static final BigDecimal MIN_CHARGE = new BigDecimal("1.00");

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 20)
	private String code;

	@Column(length = 100)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PromoKind kind;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal amount; // percent for PERCENT, rupees for FLAT

	@Column(name = "max_discount", precision = 10, scale = 2)
	private BigDecimal maxDiscount;

	@Column(name = "min_total", precision = 10, scale = 2)
	private BigDecimal minTotal;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "show_id")
	private Show show; // null: any show

	@Column(name = "valid_from", nullable = false)
	private Instant validFrom;

	@Column(name = "valid_until")
	private Instant validUntil;

	@Column(name = "max_uses")
	private Integer maxUses; // null: no limit

	@Column(name = "max_uses_per_user", nullable = false)
	private int maxUsesPerUser;

	@Column(nullable = false)
	private int uses;

	@Column(nullable = false)
	private boolean active;

	@Column(nullable = false)
	private Instant createdAt;

	@Version
	private Long version;

	public PromoCode(String code, String description, PromoKind kind, BigDecimal amount, BigDecimal maxDiscount,
			BigDecimal minTotal, Show show, Instant validFrom, Instant validUntil, Integer maxUses, int maxUsesPerUser,
			Instant now) {
		this.code = normalize(code);
		this.description = description;
		this.kind = kind;
		this.amount = amount;
		this.maxDiscount = maxDiscount;
		this.minTotal = minTotal;
		this.show = show;
		this.validFrom = validFrom;
		this.validUntil = validUntil;
		this.maxUses = maxUses;
		this.maxUsesPerUser = maxUsesPerUser;
		this.uses = 0;
		this.active = true;
		this.createdAt = now;
	}

	/** Customers may type a code in any case and with spaces around it. */
	public static String normalize(String code) {
		return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * What this code takes off a total: rounded to the paisa, capped by maxDiscount, and never more than leaves
	 * MIN_CHARGE to pay. Zero when the total is too small.
	 */
	public BigDecimal discountOn(BigDecimal subtotal) {
		BigDecimal raw = kind == PromoKind.PERCENT
				? subtotal.multiply(amount).divide(HUNDRED, 2, RoundingMode.HALF_UP)
				: amount;
		if (maxDiscount != null) {
			raw = raw.min(maxDiscount);
		}
		return raw.min(subtotal.subtract(MIN_CHARGE)).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
	}

	/** Why this code can't be used on this order now, or empty when it can (apart from the per-person limit). */
	public Optional<String> refusal(Instant now, Long showId, BigDecimal subtotal) {
		if (!active) {
			return Optional.of("This code is no longer active");
		}
		if (now.isBefore(validFrom)) {
			return Optional.of("This code isn't valid yet");
		}
		if (validUntil != null && !now.isBefore(validUntil)) {
			return Optional.of("This code has expired");
		}
		if (show != null && !show.getId().equals(showId)) {
			return Optional.of("This code is for another show");
		}
		if (isUsedUp()) {
			return Optional.of("This code has been fully used");
		}
		if (minTotal != null && subtotal.compareTo(minTotal) < 0) {
			return Optional.of("This code needs a total of at least ₹" + minTotal.toPlainString());
		}
		if (discountOn(subtotal).signum() == 0) {
			return Optional.of("This total is too small for a discount");
		}
		return Optional.empty();
	}

	public boolean isUsedUp() {
		return maxUses != null && uses >= maxUses;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	/** One more use (a booking or a payment in progress). The caller holds the row lock. */
	public void use() {
		if (isUsedUp()) {
			throw new IllegalStateException("Promo code " + code + " is used up");
		}
		uses++;
	}

	/** A use given back: the booking was cancelled or the payment never completed. */
	public void release() {
		if (uses > 0) {
			uses--;
		}
	}
}

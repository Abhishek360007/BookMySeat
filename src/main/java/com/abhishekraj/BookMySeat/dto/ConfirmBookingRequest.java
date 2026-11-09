package com.abhishekraj.BookMySeat.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ConfirmBookingRequest(
		@NotNull Long showId,
		@NotEmpty @Size(max = 10) List<@NotNull Long> seatIds,
		// Optional: a promo code, in any case
		@Size(max = 30) String promoCode
) {

	public ConfirmBookingRequest(Long showId, List<Long> seatIds) {
		this(showId, seatIds, null);
	}

	public boolean hasPromoCode() {
		return promoCode != null && !promoCode.isBlank();
	}
}

package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.PromoCode;
import com.abhishekraj.BookMySeat.entity.PromoKind;
import com.abhishekraj.BookMySeat.entity.Show;

import java.math.BigDecimal;
import java.time.Instant;

public record PromoCodeResponse(Long id, String code, String description, PromoKind kind, BigDecimal amount,
		BigDecimal maxDiscount, BigDecimal minTotal, Long showId, String showTitle, Instant validFrom,
		Instant validUntil, Integer maxUses, int maxUsesPerUser, int uses, boolean active, Instant createdAt) {

	public static PromoCodeResponse from(PromoCode promo) {
		Show show = promo.getShow();
		return new PromoCodeResponse(promo.getId(), promo.getCode(), promo.getDescription(), promo.getKind(),
				promo.getAmount(), promo.getMaxDiscount(), promo.getMinTotal(),
				show == null ? null : show.getId(), show == null ? null : show.getTitle(),
				promo.getValidFrom(), promo.getValidUntil(), promo.getMaxUses(), promo.getMaxUsesPerUser(),
				promo.getUses(), promo.isActive(), promo.getCreatedAt());
	}
}

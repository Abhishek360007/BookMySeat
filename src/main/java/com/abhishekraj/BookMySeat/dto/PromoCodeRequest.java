package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.PromoKind;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public record PromoCodeRequest(
		// Letters, digits and dashes; stored in capitals
		@NotBlank @Pattern(regexp = "^\\s*[A-Za-z0-9-]{3,20}\\s*$", message = "3 to 20 letters, digits or dashes") String code,
		@Size(max = 100) String description,
		@NotNull PromoKind kind,
		// PERCENT: 0.01 to 100 (percent); FLAT: rupees off
		@NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal amount,
		// Optional, PERCENT only: the most it takes off
		@DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal maxDiscount,
		// Optional: the smallest total it applies to
		@DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal minTotal,
		// Optional: only for this show
		Long showId,
		// Optional: from now if left out
		Instant validFrom,
		// Optional: no end if left out
		@Future Instant validUntil,
		// Optional: no limit if left out
		@Min(1) @Max(1_000_000) Integer maxUses,
		// Optional: 1 if left out
		@Min(1) @Max(100) Integer maxUsesPerUser
) {}

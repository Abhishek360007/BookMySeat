package com.abhishekraj.BookMySeat.dto;

import java.math.BigDecimal;

/** What the seats would cost with the code. Nothing is reserved: the limits are checked again when booking. */
public record PromoPreviewResponse(String code, String description, BigDecimal subtotal, BigDecimal discount,
		BigDecimal total) {}

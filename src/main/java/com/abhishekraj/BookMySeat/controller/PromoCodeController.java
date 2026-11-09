package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.PromoPreviewRequest;
import com.abhishekraj.BookMySeat.dto.PromoPreviewResponse;
import com.abhishekraj.BookMySeat.service.PromoCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Promo codes", description = "Check what a discount code takes off before booking.")
@RestController
@RequestMapping("/api/promo-codes")
@RequiredArgsConstructor
public class PromoCodeController {

	private final PromoCodeService promoCodeService;

	@Operation(summary = "Price seats with a promo code",
			description = "Subtotal, discount and total for these seats. Nothing is reserved; a code that can't be used "
					+ "is a 422 with the reason. Rate-limited per IP, so codes can't be guessed quickly.")
	@PostMapping("/preview")
	public PromoPreviewResponse preview(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PromoPreviewRequest request) {
		return promoCodeService.preview(Long.parseLong(jwt.getSubject()), request);
	}
}

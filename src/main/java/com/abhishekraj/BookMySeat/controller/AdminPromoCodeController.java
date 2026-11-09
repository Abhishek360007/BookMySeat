package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.dto.PromoCodeRequest;
import com.abhishekraj.BookMySeat.dto.PromoCodeResponse;
import com.abhishekraj.BookMySeat.service.PromoCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// Secured as ADMIN-only in SecurityConfig ("/api/admin/**")
@Tag(name = "Admin promo codes", description = "Create discount codes, see how often they were used, switch them off. ADMIN only.")
@RestController
@RequestMapping("/api/admin/promo-codes")
@RequiredArgsConstructor
public class AdminPromoCodeController {

	private final PromoCodeService promoCodeService;

	@Operation(summary = "Create a promo code",
			description = "PERCENT (optionally capped by maxDiscount) or FLAT rupees off. Optional: minTotal, one show, "
					+ "a validity window, maxUses in total; maxUsesPerUser defaults to 1. A discount never leaves less "
					+ "than ₹1 to pay.")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PromoCodeResponse create(@Valid @RequestBody PromoCodeRequest request) {
		return promoCodeService.create(request);
	}

	@Operation(summary = "Promo codes with their uses", description = "Newest first unless sort= is given.")
	@GetMapping
	public PageResponse<PromoCodeResponse> list(
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return promoCodeService.list(pageable);
	}

	@Operation(summary = "Switch a code off", description = "New bookings can't use it; bookings made with it keep their discount.")
	@PostMapping("/{id}/disable")
	public PromoCodeResponse disable(@PathVariable Long id) {
		return promoCodeService.setActive(id, false);
	}

	@Operation(summary = "Switch a code on again")
	@PostMapping("/{id}/enable")
	public PromoCodeResponse enable(@PathVariable Long id) {
		return promoCodeService.setActive(id, true);
	}
}

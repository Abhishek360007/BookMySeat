package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.AdminReviewResponse;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Secured as ADMIN-only in SecurityConfig ("/api/admin/**")
@Tag(name = "Admin reviews", description = "Moderate reviews: hide one (not listed, not in the average) or show it again. ADMIN only.")
@RestController
@RequestMapping("/api/admin/reviews")
@RequiredArgsConstructor
public class AdminReviewController {

	private final ReviewService reviewService;

	@Operation(summary = "Reviews with their show and author", description = "hidden=true or false to filter; newest first.")
	@GetMapping
	public PageResponse<AdminReviewResponse> list(@RequestParam(required = false) Boolean hidden,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return reviewService.adminList(hidden, pageable);
	}

	@Operation(summary = "Hide a review")
	@PostMapping("/{id}/hide")
	public AdminReviewResponse hide(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
		return reviewService.setHidden(Long.parseLong(jwt.getSubject()), id, true);
	}

	@Operation(summary = "Show a hidden review again")
	@PostMapping("/{id}/unhide")
	public AdminReviewResponse unhide(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
		return reviewService.setHidden(Long.parseLong(jwt.getSubject()), id, false);
	}
}

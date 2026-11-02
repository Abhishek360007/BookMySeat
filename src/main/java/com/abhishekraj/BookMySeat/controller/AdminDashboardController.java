package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.AdminOverviewResponse;
import com.abhishekraj.BookMySeat.dto.AdminShowResponse;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.service.AdminDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Secured as ADMIN-only in SecurityConfig ("/api/admin/**")
@Tag(name = "Admin reports", description = "Paginated bookings per show and a sales summary. ADMIN only.")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

	private final AdminDashboardService dashboardService;

	@Operation(summary = "Headline numbers", description = "Upcoming shows, confirmed bookings and revenue (all time and last 7 days), upcoming seats sold.")
	@GetMapping("/overview")
	public AdminOverviewResponse overview() {
		return dashboardService.overview();
	}

	@Operation(summary = "Shows with their sales",
			description = "when=UPCOMING (default), PAST or ALL; q filters by title. Sorted by start time unless sort= is given.")
	@GetMapping("/shows")
	public PageResponse<AdminShowResponse> shows(
			@RequestParam(defaultValue = "UPCOMING") AdminDashboardService.When when,
			@RequestParam(name = "q", required = false) @Size(max = 100) String text,
			@PageableDefault(size = 20, sort = "startTime") Pageable pageable) {
		return dashboardService.shows(when, text, pageable);
	}
}

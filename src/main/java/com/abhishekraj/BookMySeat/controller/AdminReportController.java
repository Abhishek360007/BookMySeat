package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.AdminBookingResponse;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.dto.ShowSummaryResponse;
import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.service.AdminReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

// Secured as ADMIN-only in SecurityConfig ("/api/admin/**")
@RestController
@RequestMapping("/api/admin/shows/{showId}")
@RequiredArgsConstructor
public class AdminReportController {

	private final AdminReportService adminReportService;

	@GetMapping("/bookings")
	public PageResponse<AdminBookingResponse> bookings(@PathVariable Long showId,
			@RequestParam(defaultValue = "CONFIRMED") BookingStatus status,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return adminReportService.bookings(showId, status, pageable);
	}

	@GetMapping("/summary")
	public ShowSummaryResponse summary(@PathVariable Long showId) {
		return adminReportService.summary(showId);
	}
}

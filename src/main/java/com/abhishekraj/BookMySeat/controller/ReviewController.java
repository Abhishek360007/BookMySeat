package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.MyReviewResponse;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.dto.ReviewRequest;
import com.abhishekraj.BookMySeat.dto.ReviewResponse;
import com.abhishekraj.BookMySeat.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Reviews", description = "Ratings and reviews of shows, by the people who went to them.")
@RestController
@RequiredArgsConstructor
public class ReviewController {

	private final ReviewService reviewService;

	@Operation(summary = "A show's reviews", description = "Visible reviews, sort=newest (default) or highest; "
			+ "the average and count are on the show itself (ratingAverage, ratingCount).")
	@GetMapping("/api/shows/{showId}/reviews")
	public PageResponse<ReviewResponse> list(@PathVariable Long showId,
			@RequestParam(defaultValue = "newest") String sort,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return reviewService.list(showId, sort, page, size);
	}

	@Operation(summary = "Write or edit your review", description = "Needs a confirmed booking for a show that has "
			+ "started (409 with reason NO_BOOKING or NOT_STARTED otherwise). 201 for your first review of the show, "
			+ "200 for an edit. One per person per show.")
	@PutMapping("/api/shows/{showId}/reviews/me")
	public ResponseEntity<MyReviewResponse> save(@AuthenticationPrincipal Jwt jwt, @PathVariable Long showId,
			@Valid @RequestBody ReviewRequest request) {
		ReviewService.Saved saved = reviewService.save(Long.parseLong(jwt.getSubject()), showId, request);
		return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK).body(saved.review());
	}

	@Operation(summary = "Delete your review")
	@DeleteMapping("/api/shows/{showId}/reviews/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long showId) {
		reviewService.delete(Long.parseLong(jwt.getSubject()), showId);
	}

	@Operation(summary = "Your reviews", description = "Newest first, including any an admin hid.")
	@GetMapping("/api/reviews/me")
	public List<MyReviewResponse> mine(@AuthenticationPrincipal Jwt jwt) {
		return reviewService.mine(Long.parseLong(jwt.getSubject()));
	}
}

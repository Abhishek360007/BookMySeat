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

@Tag(name = "Reviews", description = "Ratings and reviews of films, by the people who went to one of their screenings. "
		+ "The /api/shows/{showId}/... endpoints do the same for the film of that screening.")
@RestController
@RequiredArgsConstructor
public class ReviewController {

	private final ReviewService reviewService;

	@Operation(summary = "A film's reviews", description = "Visible reviews, sort=newest (default) or highest; "
			+ "the average and count are on the film (ratingAverage, ratingCount).")
	@GetMapping("/api/films/{filmId}/reviews")
	public PageResponse<ReviewResponse> list(@PathVariable Long filmId,
			@RequestParam(defaultValue = "newest") String sort,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return reviewService.list(filmId, sort, page, size);
	}

	@Operation(summary = "Write or edit your review of a film", description = "Needs a confirmed booking for one of "
			+ "its screenings that has started (409 with reason NO_BOOKING or NOT_STARTED otherwise). 201 for your "
			+ "first review of the film, 200 for an edit. One per person per film.")
	@PutMapping("/api/films/{filmId}/reviews/me")
	public ResponseEntity<MyReviewResponse> save(@AuthenticationPrincipal Jwt jwt, @PathVariable Long filmId,
			@Valid @RequestBody ReviewRequest request) {
		ReviewService.Saved saved = reviewService.save(Long.parseLong(jwt.getSubject()), filmId, request);
		return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK).body(saved.review());
	}

	@Operation(summary = "Delete your review of a film")
	@DeleteMapping("/api/films/{filmId}/reviews/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long filmId) {
		reviewService.delete(Long.parseLong(jwt.getSubject()), filmId);
	}

	@Operation(summary = "The reviews of a screening's film")
	@GetMapping("/api/shows/{showId}/reviews")
	public PageResponse<ReviewResponse> listForShow(@PathVariable Long showId,
			@RequestParam(defaultValue = "newest") String sort,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return reviewService.list(reviewService.filmOfShow(showId), sort, page, size);
	}

	@Operation(summary = "Write or edit your review of a screening's film")
	@PutMapping("/api/shows/{showId}/reviews/me")
	public ResponseEntity<MyReviewResponse> saveForShow(@AuthenticationPrincipal Jwt jwt, @PathVariable Long showId,
			@Valid @RequestBody ReviewRequest request) {
		return save(jwt, reviewService.filmOfShow(showId), request);
	}

	@Operation(summary = "Delete your review of a screening's film")
	@DeleteMapping("/api/shows/{showId}/reviews/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteForShow(@AuthenticationPrincipal Jwt jwt, @PathVariable Long showId) {
		delete(jwt, reviewService.filmOfShow(showId));
	}

	@Operation(summary = "Your reviews", description = "Newest first, including any an admin hid.")
	@GetMapping("/api/reviews/me")
	public List<MyReviewResponse> mine(@AuthenticationPrincipal Jwt jwt) {
		return reviewService.mine(Long.parseLong(jwt.getSubject()));
	}
}

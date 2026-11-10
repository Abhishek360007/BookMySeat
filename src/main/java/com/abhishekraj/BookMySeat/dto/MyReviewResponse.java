package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Review;

import java.time.Instant;

/** Your own review, including whether an admin hid it (then it isn't listed or counted). */
public record MyReviewResponse(Long id, Long filmId, String filmTitle, Long showId, int rating, String text,
		boolean hidden, Instant createdAt, Instant updatedAt) {

	public static MyReviewResponse from(Review review) {
		return new MyReviewResponse(review.getId(), review.getFilm().getId(), review.getFilm().getTitle(),
				review.getShow().getId(), review.getRating(), review.getBody(), review.isHidden(), review.getCreatedAt(),
				review.getUpdatedAt());
	}
}

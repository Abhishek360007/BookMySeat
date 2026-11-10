package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Review;
import com.abhishekraj.BookMySeat.service.ReviewRules;

import java.time.Instant;

/** A review as anyone sees it on the show's page: the author as "Asha K.". */
public record ReviewResponse(Long id, Long showId, int rating, String text, String author, Instant createdAt,
		boolean edited) {

	public static ReviewResponse from(Review review) {
		return new ReviewResponse(review.getId(), review.getShow().getId(), review.getRating(), review.getBody(),
				ReviewRules.displayName(review.getUser().getName()), review.getCreatedAt(), review.isEdited());
	}
}

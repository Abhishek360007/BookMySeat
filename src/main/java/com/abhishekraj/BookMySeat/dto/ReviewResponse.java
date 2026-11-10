package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Review;
import com.abhishekraj.BookMySeat.service.ReviewRules;

import java.time.Instant;

/** A review as anyone sees it on the film's page: the author as "Asha K."; showId: the screening they went to. */
public record ReviewResponse(Long id, Long filmId, Long showId, int rating, String text, String author,
		Instant createdAt, boolean edited) {

	public static ReviewResponse from(Review review) {
		return new ReviewResponse(review.getId(), review.getFilm().getId(), review.getShow().getId(), review.getRating(),
				review.getBody(), ReviewRules.displayName(review.getUser().getName()), review.getCreatedAt(), review.isEdited());
	}
}

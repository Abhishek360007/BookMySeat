package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Review;

import java.time.Instant;

/** A review for moderation: with the film and who wrote it. */
public record AdminReviewResponse(Long id, Long filmId, String filmTitle, int rating, String text, String authorName,
		String authorEmail, boolean hidden, Instant hiddenAt, Instant createdAt, boolean edited) {

	public static AdminReviewResponse from(Review review) {
		return new AdminReviewResponse(review.getId(), review.getFilm().getId(), review.getFilm().getTitle(),
				review.getRating(), review.getBody(), review.getUser().getName(), review.getUser().getEmail(),
				review.isHidden(), review.getHiddenAt(), review.getCreatedAt(), review.isEdited());
	}
}

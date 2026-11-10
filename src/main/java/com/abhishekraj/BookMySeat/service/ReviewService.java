package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.config.CacheConfig;
import com.abhishekraj.BookMySeat.dto.AdminReviewResponse;
import com.abhishekraj.BookMySeat.dto.MyReviewResponse;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.dto.ReviewRequest;
import com.abhishekraj.BookMySeat.dto.ReviewResponse;
import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.Review;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.exception.ReviewRefusedException;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.ReviewRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.support.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Reviews of shows by the people who went. Each show keeps the sum and count of its visible ratings, changed in the
 * same transaction as the review with an atomic "+ delta" UPDATE, so its average is always the one of its reviews.
 * Everything that changes one review takes its row lock (or, for a first review, the person's bookings' locks).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

	public static final int MAX_PAGE = 50;

	/** saved: the review; created: true for a first review (201), false for an edit (200). */
	public record Saved(MyReviewResponse review, boolean created) {}

	private final ReviewRepository reviewRepository;
	private final ShowRepository showRepository;
	private final BookingRepository bookingRepository;
	private final UserRepository userRepository;
	private final CacheManager cacheManager;
	private final Clock clock;

	/** Creates the person's review of the show, or edits it if they already wrote one. */
	@Transactional
	public Saved save(Long userId, Long showId, ReviewRequest request) {
		Show show = findShow(showId);
		// Locks their bookings for this show: a second save at the same moment waits here, then edits this one
		List<Booking> bookings = bookingRepository.lockConfirmed(userId, showId);
		ReviewRules.refusal(!bookings.isEmpty(), show.getStartTime(), Instant.now(clock)).ifPresent(refusal -> {
			throw new ReviewRefusedException(refusal, switch (refusal) {
				case NO_BOOKING -> "Only people with a booking for this show can review it";
				case NOT_STARTED -> "You can review this show once it has started";
			});
		});
		int rating = request.rating();
		String text = ReviewRules.text(request.text());
		Instant now = Instant.now(clock);

		Review existing = reviewRepository.lockByShowIdAndUserId(showId, userId).orElse(null);
		Review review;
		if (existing == null) {
			review = reviewRepository.save(new Review(show, userRepository.getReferenceById(userId), rating, text, now));
			showRepository.adjustRating(showId, rating, 1);
		} else {
			int delta = rating - existing.getRating();
			existing.edit(rating, text, now);
			if (!existing.isHidden() && delta != 0) {
				showRepository.adjustRating(showId, delta, 0); // a hidden review doesn't count, edited or not
			}
			review = existing;
		}
		evictShowAfterCommit(showId);
		return new Saved(MyReviewResponse.from(review), existing == null);
	}

	@Transactional
	public void delete(Long userId, Long showId) {
		Review review = reviewRepository.lockByShowIdAndUserId(showId, userId)
				.orElseThrow(() -> new ResourceNotFoundException("You haven't reviewed show " + showId));
		reviewRepository.delete(review);
		if (!review.isHidden()) {
			showRepository.adjustRating(showId, -review.getRating(), -1);
		}
		evictShowAfterCommit(showId);
	}

	/** A show's visible reviews: "newest" first, or "highest" rated first (newest first among equals). */
	@Transactional(readOnly = true)
	public PageResponse<ReviewResponse> list(Long showId, String sort, int page, int size) {
		if (!showRepository.existsById(showId)) {
			throw new ResourceNotFoundException("Show not found with id " + showId);
		}
		Sort order = switch (sort == null ? "newest" : sort.toLowerCase(Locale.ROOT)) {
			case "newest" -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
			case "highest" -> Sort.by(Sort.Order.desc("rating"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be newest or highest");
		};
		if (page < 0 || size < 1 || size > MAX_PAGE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be 0 or more and size 1 to " + MAX_PAGE);
		}
		return PageResponse.of(reviewRepository.findByShowIdAndHiddenFalse(showId, PageRequest.of(page, size, order)),
				ReviewResponse::from);
	}

	@Transactional(readOnly = true)
	public List<MyReviewResponse> mine(Long userId) {
		return reviewRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(MyReviewResponse::from).toList();
	}

	/** For moderation: every review, or only the hidden (true) or visible (false) ones. */
	@Transactional(readOnly = true)
	public PageResponse<AdminReviewResponse> adminList(Boolean hidden, Pageable pageable) {
		return PageResponse.of(hidden == null ? reviewRepository.findAll(pageable)
				: reviewRepository.findByHidden(hidden, pageable), AdminReviewResponse::from);
	}

	/** Hides a review (not listed, not in the average) or shows it again. Doing it twice changes nothing. */
	@Transactional
	public AdminReviewResponse setHidden(Long adminId, Long reviewId, boolean hidden) {
		Review review = reviewRepository.lockById(reviewId)
				.orElseThrow(() -> new ResourceNotFoundException("Review not found with id " + reviewId));
		if (review.isHidden() != hidden) {
			Long showId = review.getShow().getId();
			if (hidden) {
				review.hide(adminId, Instant.now(clock));
				showRepository.adjustRating(showId, -review.getRating(), -1);
			} else {
				review.unhide();
				showRepository.adjustRating(showId, review.getRating(), 1);
			}
			evictShowAfterCommit(showId);
		}
		return AdminReviewResponse.from(review);
	}

	// The cached show and searches carry the average: clear them once the change is visible to other readers.
	// Before the commit, a reader could put the old average straight back into the cache.
	private void evictShowAfterCommit(Long showId) {
		AfterCommit.run(() -> {
			try {
				Cache show = cacheManager.getCache(CacheConfig.SHOW);
				if (show != null) {
					show.evict(showId);
				}
				Cache search = cacheManager.getCache(CacheConfig.SHOW_SEARCH);
				if (search != null) {
					search.clear();
				}
			} catch (RuntimeException ex) {
				// Redis down: the entries run out on their own (10 minutes and 60 seconds)
				log.warn("Could not clear the cached show {} after a review: {}", showId, ex.getMessage());
			}
		});
	}

	private Show findShow(Long showId) {
		return showRepository.findById(showId)
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + showId));
	}
}

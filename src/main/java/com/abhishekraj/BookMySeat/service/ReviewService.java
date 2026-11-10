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
import com.abhishekraj.BookMySeat.repository.FilmRepository;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reviews of films by the people who went to one of their screenings: one per person per film. Each film keeps the
 * sum and count of its visible ratings, changed in the same transaction as the review with an atomic "+ delta"
 * UPDATE, so its average is always the one of its reviews. Everything that changes one review takes its row lock
 * (or, for a first review, the locks of the person's bookings for the film).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

	public static final int MAX_PAGE = 50;

	/** saved: the review; created: true for a first review (201), false for an edit (200). */
	public record Saved(MyReviewResponse review, boolean created) {}

	private final ReviewRepository reviewRepository;
	private final FilmRepository filmRepository;
	private final ShowRepository showRepository;
	private final BookingRepository bookingRepository;
	private final UserRepository userRepository;
	private final CacheManager cacheManager;
	private final Clock clock;

	/** The film a screening belongs to: the show endpoints review and list the film. */
	@Transactional(readOnly = true)
	public Long filmOfShow(Long showId) {
		return showRepository.findById(showId).map(show -> show.getFilm().getId())
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + showId));
	}

	/** Creates the person's review of the film, or edits it if they already wrote one. */
	@Transactional
	public Saved save(Long userId, Long filmId, ReviewRequest request) {
		requireFilm(filmId);
		// Locks their bookings for the film: a second save at the same moment waits here, then edits this one
		List<Booking> bookings = bookingRepository.lockConfirmedForFilm(userId, filmId);
		Instant now = Instant.now(clock);
		// The screening that lets them review: the latest one that has started, else the first one booked
		Show went = bookings.stream().map(Booking::getShow).filter(show -> !now.isBefore(show.getStartTime()))
				.max(Comparator.comparing(Show::getStartTime))
				.orElseGet(() -> bookings.stream().map(Booking::getShow).min(Comparator.comparing(Show::getStartTime)).orElse(null));
		ReviewRules.refusal(went != null, went == null ? now : went.getStartTime(), now).ifPresent(refusal -> {
			throw new ReviewRefusedException(refusal, switch (refusal) {
				case NO_BOOKING -> "Only people with a booking for this film can review it";
				case NOT_STARTED -> "You can review this film once your screening has started";
			});
		});
		int rating = request.rating();
		String text = ReviewRules.text(request.text());

		Review existing = reviewRepository.lockByFilmIdAndUserId(filmId, userId).orElse(null);
		Review review;
		if (existing == null) {
			review = reviewRepository.save(new Review(went, userRepository.getReferenceById(userId), rating, text, now));
			filmRepository.adjustRating(filmId, rating, 1);
		} else {
			int delta = rating - existing.getRating();
			existing.edit(went, rating, text, now);
			if (!existing.isHidden() && delta != 0) {
				filmRepository.adjustRating(filmId, delta, 0); // a hidden review doesn't count, edited or not
			}
			review = existing;
		}
		evictAfterCommit(filmId);
		return new Saved(MyReviewResponse.from(review), existing == null);
	}

	@Transactional
	public void delete(Long userId, Long filmId) {
		Review review = reviewRepository.lockByFilmIdAndUserId(filmId, userId)
				.orElseThrow(() -> new ResourceNotFoundException("You haven't reviewed film " + filmId));
		reviewRepository.delete(review);
		if (!review.isHidden()) {
			filmRepository.adjustRating(filmId, -review.getRating(), -1);
		}
		evictAfterCommit(filmId);
	}

	/** A film's visible reviews: "newest" first, or "highest" rated first (newest first among equals). */
	@Transactional(readOnly = true)
	public PageResponse<ReviewResponse> list(Long filmId, String sort, int page, int size) {
		requireFilm(filmId);
		Sort order = switch (sort == null ? "newest" : sort.toLowerCase(Locale.ROOT)) {
			case "newest" -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
			case "highest" -> Sort.by(Sort.Order.desc("rating"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be newest or highest");
		};
		if (page < 0 || size < 1 || size > MAX_PAGE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be 0 or more and size 1 to " + MAX_PAGE);
		}
		return PageResponse.of(reviewRepository.findByFilmIdAndHiddenFalse(filmId, PageRequest.of(page, size, order)),
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
			Long filmId = review.getFilm().getId();
			if (hidden) {
				review.hide(adminId, Instant.now(clock));
				filmRepository.adjustRating(filmId, -review.getRating(), -1);
			} else {
				review.unhide();
				filmRepository.adjustRating(filmId, review.getRating(), 1);
			}
			evictAfterCommit(filmId);
		}
		return AdminReviewResponse.from(review);
	}

	// Every cached screening of the film and every search carries the film's average: clear them once the change
	// is visible to other readers. Before the commit, a reader could put the old average straight back.
	private void evictAfterCommit(Long filmId) {
		AfterCommit.run(() -> {
			try {
				for (String name : List.of(CacheConfig.SHOW, CacheConfig.SHOW_SEARCH, CacheConfig.FILM_SEARCH)) {
					Cache cache = cacheManager.getCache(name);
					if (cache != null) {
						cache.clear();
					}
				}
			} catch (RuntimeException ex) {
				// Redis down: the entries run out on their own (10 minutes and 60 seconds)
				log.warn("Could not clear the cached shows after a review of film {}: {}", filmId, ex.getMessage());
			}
		});
	}

	private void requireFilm(Long filmId) {
		if (!filmRepository.existsById(filmId)) {
			throw new ResourceNotFoundException("Film not found with id " + filmId);
		}
	}
}

package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.ReviewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * "How was it?": afterEnd after a show is over (its start plus the film's runtime), each confirmed booking is looked
 * at once. Its customer is emailed unless they already reviewed the film or were already asked about it through
 * another booking (of this or another screening). Shows that started before the window are left alone, so a
 * sleeping app or a first deploy never emails about something long past.
 */
@Slf4j
@Service
public class ReviewRequests {

	static final int BATCH = 50;
	static final int MAX_BATCHES = 10;

	private final BookingRepository bookingRepository;
	private final ReviewRepository reviewRepository;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final Duration afterEnd;
	private final Duration window;
	private final ReviewRequests self;

	// self: calls through the Spring proxy, so each booking is handled in its own transaction
	public ReviewRequests(BookingRepository bookingRepository, ReviewRepository reviewRepository,
			ApplicationEventPublisher events, Clock clock,
			@Value("${reviews.request.after-end}") Duration afterEnd,
			@Value("${reviews.request.window}") Duration window,
			@Lazy ReviewRequests self) {
		this.bookingRepository = bookingRepository;
		this.reviewRepository = reviewRepository;
		this.events = events;
		this.clock = clock;
		this.afterEnd = afterEnd;
		this.window = window;
		this.self = self;
	}

	/** Handles every booking that is due, a batch at a time. Returns how many emails were queued. */
	public int sendDue() {
		Instant now = Instant.now(clock);
		int emailed = 0;
		for (int round = 0; round < MAX_BATCHES; round++) {
			var due = bookingRepository.findDueForReviewRequest(now.minus(afterEnd), now.minus(window), BATCH);
			for (Long id : due) {
				try {
					if (self.handle(id)) {
						emailed++;
					}
				} catch (RuntimeException ex) {
					log.warn("Could not ask for a review of booking {}: {}", id, ex.getMessage());
					return emailed; // try again on the next run rather than loop on the same booking
				}
			}
			if (due.size() < BATCH) {
				break;
			}
		}
		return emailed; // anything left (a backlog, or rows locked by another sender) waits for the next run
	}

	/** Marks the booking as looked at and, if nobody asked this person about this show yet, queues the email. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean handle(Long bookingId) {
		Booking booking = bookingRepository.lockForReviewRequest(bookingId).orElse(null);
		if (booking == null) {
			return false; // handled meanwhile, or being handled by someone else right now
		}
		Long userId = booking.getUser().getId();
		Long filmId = booking.getShow().getFilm().getId();
		boolean alreadyAsked = bookingRepository.wasAskedAboutFilm(userId, filmId);
		booking.reviewRequested(Instant.now(clock));
		if (alreadyAsked || reviewRepository.existsByFilmIdAndUserId(filmId, userId)) {
			return false;
		}
		events.publishEvent(new ReviewRequestedEvent(bookingId)); // the email is queued in this transaction
		return true;
	}
}

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
 * "How was it?": once a show is over (afterStart after it began; shows store no length, like the calendar event),
 * each confirmed booking is looked at once. Its customer is emailed unless they already reviewed the show or were
 * already asked through another booking for it. Shows older than the window are left alone, so a sleeping app or
 * a first deploy never emails about something long past.
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
	private final Duration afterStart;
	private final Duration window;
	private final ReviewRequests self;

	// self: calls through the Spring proxy, so each booking is handled in its own transaction
	public ReviewRequests(BookingRepository bookingRepository, ReviewRepository reviewRepository,
			ApplicationEventPublisher events, Clock clock,
			@Value("${reviews.request.after-start}") Duration afterStart,
			@Value("${reviews.request.window}") Duration window,
			@Lazy ReviewRequests self) {
		this.bookingRepository = bookingRepository;
		this.reviewRepository = reviewRepository;
		this.events = events;
		this.clock = clock;
		this.afterStart = afterStart;
		this.window = window;
		this.self = self;
	}

	/** Handles every booking that is due, a batch at a time. Returns how many emails were queued. */
	public int sendDue() {
		Instant now = Instant.now(clock);
		int emailed = 0;
		for (int round = 0; round < MAX_BATCHES; round++) {
			var due = bookingRepository.findDueForReviewRequest(now.minus(afterStart), now.minus(window), BATCH);
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
		Long showId = booking.getShow().getId();
		boolean alreadyAsked = bookingRepository.existsByUserIdAndShowIdAndReviewRequestedAtIsNotNull(userId, showId);
		booking.reviewRequested(Instant.now(clock));
		if (alreadyAsked || reviewRepository.existsByShowIdAndUserId(showId, userId)) {
			return false;
		}
		events.publishEvent(new ReviewRequestedEvent(bookingId)); // the email is queued in this transaction
		return true;
	}
}

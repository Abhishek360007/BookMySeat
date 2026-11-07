package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatStatus;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.WaitlistEntry;
import com.abhishekraj.BookMySeat.entity.WaitlistStatus;
import com.abhishekraj.BookMySeat.notification.EmailOutbox;
import com.abhishekraj.BookMySeat.notification.EmailTemplates;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.WaitlistRepository;
import com.abhishekraj.BookMySeat.support.AfterCommit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offers: seats that come free for a show with a waitlist are held (in Redis, like any hold) for the first people
 * in line whose request fits, for the claim window. They book them like seats they held themselves.
 * <p>
 * The hold is set inside the transaction that frees the seats, before it commits: until then the seats are still
 * BOOKED for everyone else, so nobody can grab them in between. If that transaction rolls back, the hold is removed.
 */
@Slf4j
@Service
public class WaitlistOffers {

	private final WaitlistRepository waitlistRepository;
	private final SeatRepository seatRepository;
	private final ShowRepository showRepository;
	private final BookingRepository bookingRepository;
	private final SeatHoldService seatHoldService;
	private final EmailOutbox outbox;
	private final EmailTemplates templates;
	private final WaitlistMetrics metrics;
	private final Clock clock;
	private final Duration claimWindow;
	private final TransactionTemplate newTransaction;

	public WaitlistOffers(WaitlistRepository waitlistRepository, SeatRepository seatRepository, ShowRepository showRepository,
			BookingRepository bookingRepository, SeatHoldService seatHoldService, EmailOutbox outbox, EmailTemplates templates,
			WaitlistMetrics metrics, Clock clock, @Value("${waitlist.claim-window}") Duration claimWindow,
			PlatformTransactionManager transactionManager) {
		this.waitlistRepository = waitlistRepository;
		this.seatRepository = seatRepository;
		this.showRepository = showRepository;
		this.bookingRepository = bookingRepository;
		this.seatHoldService = seatHoldService;
		this.outbox = outbox;
		this.templates = templates;
		this.metrics = metrics;
		this.clock = clock;
		this.claimWindow = claimWindow;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** A customer cancelled: their seats go to the line first (a cancelled show's line is closed instead, see 21.3). */
	@EventListener
	public void onBookingCancelled(BookingCancelledEvent event) {
		if (event.showCancelled()) {
			return;
		}
		bookingRepository.findById(event.bookingId())
				.ifPresent(booking -> offer(booking.getShow(), booking.getSeats()));
	}

	/**
	 * Booking a show takes you off its waitlist. Offered seats you didn't book are released and offered on,
	 * once this booking has committed.
	 */
	@EventListener
	public void onBookingConfirmed(BookingConfirmedEvent event) {
		Booking booking = bookingRepository.findById(event.bookingId()).orElse(null);
		if (booking == null) {
			return;
		}
		Long showId = booking.getShow().getId();
		Long userId = booking.getUser().getId();
		waitlistRepository.findByShowIdAndUserIdAndStatusIn(showId, userId, WaitlistService.ACTIVE).ifPresent(entry -> {
			boolean hadOffer = entry.getStatus() == WaitlistStatus.OFFERED;
			Set<Long> booked = new HashSet<>(booking.getSeats().stream().map(Seat::getId).toList());
			List<Long> notBooked = entry.getOfferedSeats().stream().map(Seat::getId).filter(id -> !booked.contains(id)).toList();
			entry.end(WaitlistStatus.CLAIMED, Instant.now(clock));
			if (hadOffer) {
				metrics.offerEnded(WaitlistStatus.CLAIMED);
			}
			if (!notBooked.isEmpty()) {
				AfterCommit.run(() -> {
					seatHoldService.releaseAll(showId, notBooked, userId);
					offerFreeSeats(showId);
				});
			}
		});
	}

	/**
	 * In its own transaction: offers every seat of the show that is free and held by no one to the line.
	 * Used after an offer ends early, and by the sweep job. Never throws (the next sweep tries again).
	 */
	public int offerFreeSeats(Long showId) {
		try {
			Integer offered = newTransaction.execute(status -> showRepository.findById(showId)
					.map(show -> offer(show, seatRepository.findByShowIdAndStatusOrderByIdAsc(show.getId(), SeatStatus.AVAILABLE)))
					.orElse(0));
			return offered == null ? 0 : offered;
		} catch (RuntimeException ex) {
			log.warn("Could not offer free seats of show {} to its waitlist (the sweep retries)", showId, ex);
			return 0;
		}
	}

	/**
	 * Offers these seats (AVAILABLE in this transaction) to the line, first come first served: each person gets
	 * exactly the number of seats they asked for, so someone who wants more than is free is skipped, not split.
	 * Returns how many people got an offer.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int offer(Show show, Collection<Seat> seats) {
		Instant now = Instant.now(clock);
		if (show.isCancelled() || !now.isBefore(show.getStartTime()) || seats.isEmpty()) {
			return 0;
		}
		Instant expiresAt = now.plus(claimWindow).isBefore(show.getStartTime()) ? now.plus(claimWindow) : show.getStartTime();

		List<Seat> free = new ArrayList<>(seats.stream().sorted(Comparator.comparing(Seat::getId)).toList());
		Set<Long> heldByAnyone = seatHoldService.findHeldSeatIds(show.getId(), free.stream().map(Seat::getId).toList());
		free.removeIf(seat -> heldByAnyone.contains(seat.getId()));

		int offered = 0;
		for (WaitlistEntry entry : free.isEmpty() ? List.<WaitlistEntry>of() : waitlistRepository.lockWaitingLine(show.getId())) {
			if (free.isEmpty()) {
				break;
			}
			if (entry.getSeatsWanted() > free.size()) {
				continue;
			}
			List<Seat> picked = List.copyOf(free.subList(0, entry.getSeatsWanted())); // in seat order, so mostly side by side
			List<Long> seatIds = picked.stream().map(Seat::getId).toList();
			Long userId = entry.getUser().getId();
			if (!seatHoldService.holdAll(show.getId(), seatIds, userId, Duration.between(now, expiresAt))) {
				// Someone held one of them a moment ago: those seats aren't free; this person stays first in line
				free.removeAll(picked);
				continue;
			}
			releaseIfRolledBack(show.getId(), seatIds, userId);
			entry.offer(new HashSet<>(picked), now, expiresAt);
			free.removeAll(picked);
			outbox.enqueue("WAITLIST_OFFER", templates.waitlistOffer(entry));
			metrics.offered();
			offered++;
		}
		return offered;
	}

	private void releaseIfRolledBack(Long showId, List<Long> seatIds, Long userId) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					seatHoldService.releaseAll(showId, seatIds, userId);
				}
			}
		});
	}
}

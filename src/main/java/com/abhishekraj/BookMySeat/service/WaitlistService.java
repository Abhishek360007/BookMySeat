package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.WaitlistRequest;
import com.abhishekraj.BookMySeat.dto.WaitlistResponse;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.SeatStatus;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.WaitlistEntry;
import com.abhishekraj.BookMySeat.entity.WaitlistStatus;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.repository.WaitlistRepository;
import com.abhishekraj.BookMySeat.support.AfterCommit;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Joining and leaving the waitlist of a sold-out show. Offers of seats that come free: WaitlistOffers. */
@Service
@RequiredArgsConstructor
public class WaitlistService {

	static final Set<WaitlistStatus> ACTIVE = EnumSet.of(WaitlistStatus.WAITING, WaitlistStatus.OFFERED);

	private final WaitlistRepository waitlistRepository;
	private final ShowRepository showRepository;
	private final SeatRepository seatRepository;
	private final UserRepository userRepository;
	private final SeatHoldService seatHoldService;
	private final WaitlistMetrics metrics;
	private final WaitlistOffers offers;
	private final Clock clock;

	@Transactional
	public WaitlistResponse join(Long userId, WaitlistRequest request) {
		Show show = showRepository.findById(request.showId())
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + request.showId()));
		if (show.isCancelled()) {
			throw new ConflictException("Show " + show.getId() + " has been cancelled");
		}
		if (!Instant.now(clock).isBefore(show.getStartTime())) {
			throw new ConflictException("Show " + show.getId() + " has already started");
		}
		if (!seatRepository.existsByShowId(show.getId())) {
			throw new ConflictException("Show " + show.getId() + " has no seats yet");
		}
		// Sold out = every seat is booked or held (by a customer paying, or for a waitlist offer).
		// A held seat that comes free again goes to the line (the sweep offers it), so nobody waits for nothing.
		List<Long> free = seatRepository.findByShowIdAndStatusOrderByIdAsc(show.getId(), SeatStatus.AVAILABLE)
				.stream().map(Seat::getId).toList();
		if (seatHoldService.findHeldSeatIds(show.getId(), free).size() < free.size()) {
			throw new ConflictException("Seats are still available for this show: book them directly");
		}
		if (waitlistRepository.findByShowIdAndUserIdAndStatusIn(show.getId(), userId, ACTIVE).isPresent()) {
			throw new ConflictException("You are already on the waitlist for this show");
		}
		WaitlistEntry entry;
		try {
			entry = waitlistRepository.saveAndFlush(
					new WaitlistEntry(show, userRepository.getReferenceById(userId), request.seats(), Instant.now(clock)));
		} catch (DataIntegrityViolationException raced) {
			// Two joins at once: the partial unique index lets only one in
			throw new ConflictException("You are already on the waitlist for this show");
		}
		metrics.joined();
		return WaitlistResponse.from(entry, position(entry));
	}

	/** Leaving with an offer declines it: the held seats go to the next person in line right away. */
	@Transactional
	public void leave(Long userId, Long showId) {
		WaitlistEntry entry = waitlistRepository.findByShowIdAndUserIdAndStatusIn(showId, userId, ACTIVE)
				.orElseThrow(() -> new ResourceNotFoundException("You are not on the waitlist for show " + showId));
		if (entry.getStatus() == WaitlistStatus.OFFERED) {
			List<Long> offered = entry.getOfferedSeats().stream().map(Seat::getId).toList();
			metrics.offerEnded(WaitlistStatus.LEFT);
			AfterCommit.run(() -> {
				seatHoldService.releaseAll(showId, offered, userId);
				offers.offerFreeSeats(showId);
			});
		}
		entry.end(WaitlistStatus.LEFT, Instant.now(clock));
	}

	/** Your places in line (waiting or with an offer), oldest first. */
	@Transactional(readOnly = true)
	public List<WaitlistResponse> mine(Long userId) {
		return waitlistRepository.findByUserIdAndStatusInOrderByIdAsc(userId, ACTIVE).stream()
				.map(entry -> WaitlistResponse.from(entry, position(entry)))
				.toList();
	}

	private Integer position(WaitlistEntry entry) {
		if (entry.getStatus() != WaitlistStatus.WAITING) {
			return null;
		}
		return (int) waitlistRepository.countWaitingAhead(entry.getShow().getId(), entry.getId()) + 1;
	}
}

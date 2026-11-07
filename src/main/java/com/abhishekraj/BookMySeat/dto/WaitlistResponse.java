package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.WaitlistEntry;
import com.abhishekraj.BookMySeat.entity.WaitlistStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * position: 1 = next in line (only while WAITING). While OFFERED, offeredSeatIds are held for you until
 * offerExpiresAt: book them like any other held seats.
 */
public record WaitlistResponse(Long id, Long showId, String showTitle, Instant showStartTime, String venueName,
		int seats, WaitlistStatus status, Integer position, Instant joinedAt,
		List<Long> offeredSeatIds, List<String> offeredSeatNumbers, Instant offerExpiresAt) {

	public static WaitlistResponse from(WaitlistEntry entry, Integer position) {
		List<Seat> offered = entry.getOfferedSeats().stream().sorted(Comparator.comparing(Seat::getId)).toList();
		return new WaitlistResponse(entry.getId(), entry.getShow().getId(), entry.getShow().getTitle(),
				entry.getShow().getStartTime(), entry.getShow().getVenue().getName(), entry.getSeatsWanted(),
				entry.getStatus(), position, entry.getCreatedAt(),
				offered.stream().map(Seat::getId).toList(), offered.stream().map(Seat::getSeatNumber).toList(),
				entry.getOfferExpiresAt());
	}
}

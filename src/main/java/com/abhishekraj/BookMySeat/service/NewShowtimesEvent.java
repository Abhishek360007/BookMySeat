package com.abhishekraj.BookMySeat.service;

import java.util.List;

/** A film got new showtimes (showIds, soonest first) and these people follow it: one email each (BookingEmails). */
public record NewShowtimesEvent(Long filmId, List<Long> showIds, List<Long> followerIds) {}

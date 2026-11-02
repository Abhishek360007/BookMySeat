package com.abhishekraj.BookMySeat.service;

/** Published inside the confirm transaction (direct or paid), so listeners (e.g. the email) commit with it. */
public record BookingConfirmedEvent(Long bookingId) {}

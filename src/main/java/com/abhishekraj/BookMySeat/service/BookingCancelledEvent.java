package com.abhishekraj.BookMySeat.service;

/** Published inside the cancel transaction, so listeners (e.g. refunds) commit or roll back together with it. */
public record BookingCancelledEvent(Long bookingId) {}

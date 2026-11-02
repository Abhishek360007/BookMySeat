package com.abhishekraj.BookMySeat.service;

/**
 * Published inside the cancel transaction, so listeners (e.g. refunds, the email) commit or roll back together
 * with it. showCancelled: the venue cancelled the whole show, not the customer their booking.
 */
public record BookingCancelledEvent(Long bookingId, boolean showCancelled) {}

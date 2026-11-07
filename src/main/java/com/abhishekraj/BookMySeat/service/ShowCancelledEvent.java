package com.abhishekraj.BookMySeat.service;

/** Published inside the show's cancel transaction, after its bookings were cancelled (e.g. to close its waitlist). */
public record ShowCancelledEvent(Long showId) {}

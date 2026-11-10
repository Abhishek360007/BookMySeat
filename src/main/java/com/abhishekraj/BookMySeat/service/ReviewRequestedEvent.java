package com.abhishekraj.BookMySeat.service;

/** A booking's show is over and its customer hasn't reviewed the film: ask them how it was (BookingEmails). */
public record ReviewRequestedEvent(Long bookingId) {}

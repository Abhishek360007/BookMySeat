package com.abhishekraj.BookMySeat.dto;

/** Result of cancelling a show: its confirmed bookings were cancelled, and paid ones are being refunded. */
public record ShowCancelResponse(Long showId, long cancelledBookings) {}

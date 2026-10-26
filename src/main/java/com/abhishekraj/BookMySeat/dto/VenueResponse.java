package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Venue;

public record VenueResponse(Long id, String name, String city, String address) {

	public static VenueResponse from(Venue venue) {
		return new VenueResponse(venue.getId(), venue.getName(), venue.getCity(), venue.getAddress());
	}
}

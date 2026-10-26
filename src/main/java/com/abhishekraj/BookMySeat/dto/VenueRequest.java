package com.abhishekraj.BookMySeat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VenueRequest(
		@NotBlank @Size(max = 100) String name,
		@NotBlank @Size(max = 50) String city,
		@Size(max = 255) String address
) {}

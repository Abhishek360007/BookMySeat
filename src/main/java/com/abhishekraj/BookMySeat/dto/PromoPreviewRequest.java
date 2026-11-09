package com.abhishekraj.BookMySeat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PromoPreviewRequest(
		@NotBlank @Size(max = 30) String code,
		@NotNull Long showId,
		@NotEmpty @Size(max = 10) List<@NotNull Long> seatIds
) {}

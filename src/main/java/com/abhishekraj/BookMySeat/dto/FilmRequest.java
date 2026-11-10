package com.abhishekraj.BookMySeat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** certificate: U, UA, A or S (Indian film certificates), optional. */
public record FilmRequest(
		@NotBlank @Size(max = 150) String title,
		@Size(max = 2000) String synopsis,
		@NotNull @Min(1) @Max(600) Integer runtimeMinutes,
		@Size(max = 40) String language,
		@Pattern(regexp = "U|UA|A|S", message = "must be U, UA, A or S") String certificate
) {}

package com.abhishekraj.BookMySeat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** showId: the show being checked in at this door, so a ticket for another show is caught. */
public record CheckInRequest(@NotNull Long showId, @NotBlank @Size(max = 100) String code) {}

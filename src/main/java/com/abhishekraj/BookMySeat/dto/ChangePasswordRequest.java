package com.abhishekraj.BookMySeat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record ChangePasswordRequest(
		@NotBlank @Size(max = 72) String currentPassword,
		@NotBlank @Size(min = 8, max = 72) String newPassword
) {

	// Same BCrypt limit as registration: 72 bytes
	@JsonIgnore
	@AssertTrue(message = "password must be at most 72 bytes")
	public boolean isPasswordWithinBcryptLimit() {
		return newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
	}
}

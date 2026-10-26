package com.abhishekraj.BookMySeat.dto;

import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.entity.User;

public record UserResponse(Long id, String name, String email, Role role) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
	}
}

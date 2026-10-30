package com.abhishekraj.BookMySeat.dto;

public record LoginResponse(String accessToken, String tokenType, long expiresIn,
		String refreshToken, long refreshExpiresIn) {}

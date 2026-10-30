package com.abhishekraj.BookMySeat.exception;

public class InvalidRefreshTokenException extends RuntimeException {
	public InvalidRefreshTokenException() {
		super("Refresh token is invalid, expired or revoked. Log in again.");
	}
}

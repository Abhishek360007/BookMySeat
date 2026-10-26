package com.abhishekraj.BookMySeat.exception;

public class InvalidCredentialsException extends RuntimeException {
	public InvalidCredentialsException() {
		super("Invalid email or password");
	}
}

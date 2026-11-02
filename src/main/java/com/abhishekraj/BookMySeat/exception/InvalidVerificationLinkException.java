package com.abhishekraj.BookMySeat.exception;

public class InvalidVerificationLinkException extends RuntimeException {
	public InvalidVerificationLinkException() {
		super("This verification link is invalid or has expired. Log in and ask for a new one.");
	}
}

package com.abhishekraj.BookMySeat.exception;

public class InvalidResetLinkException extends RuntimeException {
	public InvalidResetLinkException() {
		super("This password reset link is invalid, already used or expired. Ask for a new one.");
	}
}

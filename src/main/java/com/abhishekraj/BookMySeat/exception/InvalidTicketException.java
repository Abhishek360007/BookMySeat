package com.abhishekraj.BookMySeat.exception;

public class InvalidTicketException extends RuntimeException {
	public InvalidTicketException() {
		super("This is not a valid BookMySeat ticket");
	}
}

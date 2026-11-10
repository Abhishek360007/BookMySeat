package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.dto.TicketResponse;
import com.abhishekraj.BookMySeat.ticket.CalendarEvents;
import com.abhishekraj.BookMySeat.ticket.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tickets", description = "E-tickets for your confirmed bookings, and checking them in at the door (ADMIN).")
@RestController
@RequestMapping("/api/bookings/{id}")
@RequiredArgsConstructor
public class TicketController {

	private final TicketService ticketService;

	@Operation(summary = "Your ticket", description = "The signed ticket code (what the QR contains) and the details "
			+ "printed on the ticket. 404 for someone else's booking, 409 for a cancelled one.")
	@GetMapping("/ticket")
	public TicketResponse ticket(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
		return ticketService.ticket(Long.parseLong(jwt.getSubject()), id);
	}

	@Operation(summary = "Your ticket as a QR code image (PNG)")
	@GetMapping(value = "/ticket.png", produces = MediaType.IMAGE_PNG_VALUE)
	public byte[] ticketPng(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
		return ticketService.qrPng(ticketService.ticket(Long.parseLong(jwt.getSubject()), id));
	}

	@Operation(summary = "Your booking as a calendar event (.ics)", description = "For Google Calendar, Outlook or "
			+ "Apple Calendar, with the show's current time; importing a newer file moves the event (same UID, "
			+ "higher SEQUENCE). 404 for someone else's booking, 409 for a cancelled one.")
	@GetMapping(value = "/ticket.ics", produces = CalendarEvents.CONTENT_TYPE)
	public ResponseEntity<byte[]> ticketIcs(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
		TicketService.CalendarFile file = ticketService.calendarEvent(Long.parseLong(jwt.getSubject()), id);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(file.filename()).build().toString())
				.contentType(MediaType.parseMediaType(CalendarEvents.CONTENT_TYPE))
				.body(file.content());
	}
}

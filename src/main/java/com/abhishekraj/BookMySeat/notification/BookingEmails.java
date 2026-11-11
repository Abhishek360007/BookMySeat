package com.abhishekraj.BookMySeat.notification;

import com.abhishekraj.BookMySeat.entity.BookingStatus;
import com.abhishekraj.BookMySeat.entity.PaymentStatus;
import com.abhishekraj.BookMySeat.payment.PaymentRefundedEvent;
import com.abhishekraj.BookMySeat.entity.Film;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.FilmRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.service.NewShowtimesEvent;
import com.abhishekraj.BookMySeat.repository.PaymentRepository;
import com.abhishekraj.BookMySeat.service.BookingCancelledEvent;
import com.abhishekraj.BookMySeat.service.BookingConfirmedEvent;
import com.abhishekraj.BookMySeat.service.ReviewRequestedEvent;
import com.abhishekraj.BookMySeat.service.ShowRescheduledEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

// Plain @EventListener, not @TransactionalEventListener: the email is queued in the same transaction
// as the booking change, so both commit or neither does (the outbox delivers it after commit)
@Component
@RequiredArgsConstructor
public class BookingEmails {

	private final BookingRepository bookingRepository;
	private final FilmRepository filmRepository;
	private final ShowRepository showRepository;
	private final UserRepository userRepository;
	private final PaymentRepository paymentRepository;
	private final EmailTemplates templates;
	private final EmailOutbox outbox;

	@EventListener
	public void onConfirmed(BookingConfirmedEvent event) {
		bookingRepository.findById(event.bookingId())
				.ifPresent(booking -> outbox.enqueue("BOOKING_CONFIRMED", templates.bookingConfirmed(booking)));
	}

	@EventListener
	public void onCancelled(BookingCancelledEvent event) {
		bookingRepository.findById(event.bookingId()).ifPresent(booking -> {
			// The refund listener may already have moved the payment on; either way money is going back
			boolean paid = paymentRepository.findByBookingId(booking.getId())
					.filter(p -> p.getStatus() == PaymentStatus.REFUND_PENDING || p.getStatus() == PaymentStatus.CONFIRMED)
					.isPresent();
			outbox.enqueue(event.showCancelled() ? "SHOW_CANCELLED" : "BOOKING_CANCELLED",
					templates.bookingCancelled(booking, paid, event.showCancelled()));
		});
	}

	@EventListener
	public void onRescheduled(ShowRescheduledEvent event) {
		bookingRepository.findAllByShowIdAndStatus(event.showId(), BookingStatus.CONFIRMED).forEach(booking ->
				outbox.enqueue("SHOW_RESCHEDULED", templates.showRescheduled(booking, event.previousStartTime())));
	}

	@EventListener
	public void onNewShowtimes(NewShowtimesEvent event) {
		Film film = filmRepository.findById(event.filmId()).orElse(null);
		if (film == null) {
			return;
		}
		List<Show> shows = showRepository.findAllById(event.showIds()).stream()
				.sorted(Comparator.comparing(Show::getStartTime)).toList();
		userRepository.findAllById(event.followerIds())
				.forEach(user -> outbox.enqueue("NEW_SHOWTIMES", templates.newShowtimes(user, film, shows)));
	}

	@EventListener
	public void onReviewRequested(ReviewRequestedEvent event) {
		bookingRepository.findById(event.bookingId())
				.ifPresent(booking -> outbox.enqueue("REVIEW_REQUEST", templates.reviewRequest(booking)));
	}

	@EventListener
	public void onRefunded(PaymentRefundedEvent event) {
		paymentRepository.findByOrderId(event.orderId())
				.ifPresent(payment -> outbox.enqueue("PAYMENT_REFUNDED", templates.paymentRefunded(payment)));
	}
}

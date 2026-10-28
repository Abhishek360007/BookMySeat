package com.abhishekraj.BookMySeat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "bookings", indexes = @Index(name = "idx_bookings_user", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_id")
	private Show show;

	// No unique constraint on seat_id: a seat can be in a cancelled booking and later in a new one
	@ManyToMany
	@JoinTable(name = "booking_seats",
			joinColumns = @JoinColumn(name = "booking_id"),
			inverseJoinColumns = @JoinColumn(name = "seat_id"))
	private Set<Seat> seats = new HashSet<>();

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private BookingStatus status;

	// Snapshot at booking time: later price changes must not alter past bookings
	@Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
	private BigDecimal totalAmount;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant cancelledAt;

	@Version
	private Long version;

	public Booking(User user, Show show, Set<Seat> seats) {
		this.user = user;
		this.show = show;
		this.seats = seats;
		this.status = BookingStatus.CONFIRMED;
		this.totalAmount = show.getPrice().multiply(BigDecimal.valueOf(seats.size()));
		this.createdAt = Instant.now();
	}

	public void cancel() {
		this.status = BookingStatus.CANCELLED;
		this.cancelledAt = Instant.now();
		seats.forEach(seat -> seat.setStatus(SeatStatus.AVAILABLE));
	}
}

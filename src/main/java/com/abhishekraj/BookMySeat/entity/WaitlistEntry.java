package com.abhishekraj.BookMySeat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "waitlist_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaitlistEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id; // also the order in line: a lower id joined earlier

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_id")
	private Show show;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private User user;

	@Column(name = "seats_wanted", nullable = false)
	private int seatsWanted;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private WaitlistStatus status;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant offeredAt;

	private Instant offerExpiresAt;

	private Instant endedAt;

	// The seats held for this person while the offer lasts
	@ManyToMany
	@JoinTable(name = "waitlist_offer_seats",
			joinColumns = @JoinColumn(name = "entry_id"),
			inverseJoinColumns = @JoinColumn(name = "seat_id"))
	private Set<Seat> offeredSeats = new HashSet<>();

	@Version
	private Long version;

	public WaitlistEntry(Show show, User user, int seatsWanted, Instant now) {
		this.show = show;
		this.user = user;
		this.seatsWanted = seatsWanted;
		this.status = WaitlistStatus.WAITING;
		this.createdAt = now;
	}

	/** These seats are held for this person until expiresAt. */
	public void offer(Set<Seat> seats, Instant now, Instant expiresAt) {
		this.status = WaitlistStatus.OFFERED;
		this.offeredSeats = seats;
		this.offeredAt = now;
		this.offerExpiresAt = expiresAt;
	}

	public void end(WaitlistStatus how, Instant now) {
		this.status = how;
		this.endedAt = now;
	}
}

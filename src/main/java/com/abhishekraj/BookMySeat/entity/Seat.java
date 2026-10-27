package com.abhishekraj.BookMySeat.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "seats",
		uniqueConstraints = @UniqueConstraint(columnNames = {"show_id", "seat_number"}))
@Getter
@Setter
@NoArgsConstructor
public class Seat {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_id")
	private Show show;

	@Column(name = "seat_number", nullable = false, length = 10)
	private String seatNumber;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SeatStatus status = SeatStatus.AVAILABLE;

	@Version
	private Long version;
}

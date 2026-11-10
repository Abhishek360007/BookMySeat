package com.abhishekraj.BookMySeat.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "shows")
@Getter
@Setter
@NoArgsConstructor
public class Show {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// The film's title, kept on the show for everything that reads it (emails, tickets, bookings)
	@Column(nullable = false, length = 150)
	private String title;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "film_id")
	private Film film;

	@Column(nullable = false)
	private Instant startTime;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal price;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "venue_id")
	private Venue venue;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ShowStatus status = ShowStatus.SCHEDULED;

	private Instant cancelledAt;

	// Prices of the non-regular categories; REGULAR seats cost `price`
	@ElementCollection
	@CollectionTable(name = "show_category_prices", joinColumns = @JoinColumn(name = "show_id"))
	@MapKeyEnumerated(EnumType.STRING)
	@MapKeyColumn(name = "category", length = 20)
	@Column(name = "price", nullable = false, precision = 10, scale = 2)
	private Map<SeatCategory, BigDecimal> categoryPrices = new EnumMap<>(SeatCategory.class);

	@Column(name = "aisle_after", length = 50)
	private String aisleAfter; // e.g. "4,8": aisles after seat 4 and seat 8 of every row

	/** What one seat of this category costs now (bookings keep the total they were made with). */
	public BigDecimal priceOf(SeatCategory category) {
		return category == SeatCategory.REGULAR ? price : categoryPrices.getOrDefault(category, price);
	}

	public BigDecimal totalFor(Collection<Seat> seats) {
		return seats.stream().map(seat -> priceOf(seat.getCategory())).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/** Every category with its price, regular first. */
	public Map<SeatCategory, BigDecimal> allPrices() {
		Map<SeatCategory, BigDecimal> all = new EnumMap<>(SeatCategory.class);
		for (SeatCategory category : SeatCategory.values()) {
			all.put(category, priceOf(category));
		}
		return all;
	}

	public List<Integer> aisleAfterList() {
		if (aisleAfter == null || aisleAfter.isBlank()) {
			return List.of();
		}
		return Arrays.stream(aisleAfter.split(",")).map(String::trim).map(Integer::valueOf).sorted().toList();
	}

	/** When the show is over: its start plus the film's runtime. */
	public Instant endTime() {
		return startTime.plus(film.runtime());
	}

	public boolean isCancelled() {
		return status == ShowStatus.CANCELLED;
	}

	public void cancel(Instant now) {
		this.status = ShowStatus.CANCELLED;
		this.cancelledAt = now;
	}
}

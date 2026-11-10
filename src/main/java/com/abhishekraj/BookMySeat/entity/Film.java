package com.abhishekraj.BookMySeat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;

/** A film: what its screenings (shows) have in common, and its reviews' rating. One per title, ignoring case. */
@Entity
@Table(name = "films")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Film {

	/** Until an admin says otherwise: what the calendar event and the review emails assumed before films existed. */
	public static final int DEFAULT_RUNTIME_MINUTES = 180;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 150)
	private String title;

	@Column(length = 2000)
	private String synopsis;

	@Column(nullable = false)
	private int runtimeMinutes = DEFAULT_RUNTIME_MINUTES;

	@Column(length = 40)
	private String language;

	@Column(length = 4)
	private String certificate; // U, UA, A or S (Indian film certificates)

	// Sum and count of the visible review ratings. Only ever changed by FilmRepository.adjustRating (an atomic
	// "+ delta" UPDATE), never written from here: saving an edited film can't overwrite a review counted meanwhile
	@Column(nullable = false, insertable = false, updatable = false)
	private int ratingSum;

	@Column(nullable = false, insertable = false, updatable = false)
	private int ratingCount;

	@Column(nullable = false)
	private Instant createdAt;

	public Film(String title, int runtimeMinutes, Instant now) {
		this.title = title;
		this.runtimeMinutes = runtimeMinutes;
		this.createdAt = now;
	}

	public Duration runtime() {
		return Duration.ofMinutes(runtimeMinutes);
	}
}

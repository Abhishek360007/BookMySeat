package com.abhishekraj.BookMySeat.film;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/** Someone following a film: they are emailed when it gets new showtimes. */
@Entity
@Table(name = "film_follows")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FilmFollow {

	@Embeddable
	public record Id(@Column(name = "user_id") Long userId, @Column(name = "film_id") Long filmId) implements Serializable {}

	@EmbeddedId
	private Id id;

	@Column(nullable = false)
	private Instant createdAt;
}

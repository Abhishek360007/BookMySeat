package com.abhishekraj.BookMySeat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One person's rating (1 to 5) and optional text for a show they went to. One per person per show. */
@Entity
@Table(name = "reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_id")
	private Show show;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private User user;

	@Column(nullable = false)
	private int rating;

	@Column(length = 1000)
	private String body;

	@Column(nullable = false)
	private boolean hidden;

	private Instant hiddenAt;

	private Long hiddenBy;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	public Review(Show show, User user, int rating, String body, Instant now) {
		this.show = show;
		this.user = user;
		this.rating = rating;
		this.body = body;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public void edit(int rating, String body, Instant now) {
		this.rating = rating;
		this.body = body;
		this.updatedAt = now;
	}

	public void hide(Long adminId, Instant now) {
		this.hidden = true;
		this.hiddenAt = now;
		this.hiddenBy = adminId;
	}

	public void unhide() {
		this.hidden = false;
		this.hiddenAt = null;
		this.hiddenBy = null;
	}

	public boolean isEdited() {
		return updatedAt.isAfter(createdAt);
	}
}

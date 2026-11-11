package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.film.FilmFollowRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Tells a film's followers about its new showtimes: each show once, `after` it was created (an admin may still fix or
 * cancel a show just made), and all of a film's new showtimes in one email per follower. A cancelled show, or one
 * that has started by then, is marked as done without being mentioned.
 */
@Slf4j
@Service
public class ShowAnnouncements {

	static final int FILMS_PER_RUN = 50;

	private final ShowRepository showRepository;
	private final FilmFollowRepository followRepository;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final Duration after;
	private final ShowAnnouncements self;

	// self: calls through the Spring proxy, so each film is handled in its own transaction
	public ShowAnnouncements(ShowRepository showRepository, FilmFollowRepository followRepository,
			ApplicationEventPublisher events, Clock clock, @Value("${showtimes.announce.after}") Duration after,
			@Lazy ShowAnnouncements self) {
		this.showRepository = showRepository;
		this.followRepository = followRepository;
		this.events = events;
		this.clock = clock;
		this.after = after;
		this.self = self;
	}

	/** Returns how many emails were queued. */
	public int announceDue() {
		Instant cutoff = Instant.now(clock).minus(after);
		int emails = 0;
		for (Long filmId : showRepository.findFilmsToAnnounce(cutoff, FILMS_PER_RUN)) {
			try {
				emails += self.announce(filmId, cutoff);
			} catch (RuntimeException ex) {
				log.warn("Could not announce the new showtimes of film {}: {}", filmId, ex.getMessage());
			}
		}
		return emails;
	}

	/** One film: its shows created before the cutoff and not announced yet, locked so two runs never both send. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int announce(Long filmId, Instant cutoff) {
		Instant now = Instant.now(clock);
		List<Show> shows = showRepository.lockToAnnounce(filmId, cutoff);
		shows.forEach(show -> show.setFollowersNotifiedAt(now));
		List<Long> news = shows.stream()
				.filter(show -> !show.isCancelled() && show.getStartTime().isAfter(now))
				.map(Show::getId)
				.toList();
		if (news.isEmpty()) {
			return 0;
		}
		List<Long> followers = followRepository.followersOf(filmId);
		if (followers.isEmpty()) {
			return 0;
		}
		events.publishEvent(new NewShowtimesEvent(filmId, news, followers)); // the emails are queued in this transaction
		return followers.size();
	}
}

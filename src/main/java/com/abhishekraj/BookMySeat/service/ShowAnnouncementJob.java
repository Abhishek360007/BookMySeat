package com.abhishekraj.BookMySeat.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Every few minutes (see ShowAnnouncements): emails films' followers about new showtimes.
// One instance at a time (ShedLock); a missed run (the app asleep) is caught up by the next.
@Component
@RequiredArgsConstructor
public class ShowAnnouncementJob {

	private final ShowAnnouncements announcements;

	@Scheduled(initialDelayString = "${showtimes.announce.initial-delay}", fixedDelayString = "${showtimes.announce.interval}")
	@SchedulerLock(name = "showAnnouncements", lockAtLeastFor = "PT20S")
	public void run() {
		announcements.announceDue();
	}
}

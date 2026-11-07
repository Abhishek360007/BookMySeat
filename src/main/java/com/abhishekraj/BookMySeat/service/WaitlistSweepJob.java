package com.abhishekraj.BookMySeat.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Every minute (see WaitlistOffers.sweep): ends expired offers, closes lines of past or cancelled shows, and offers
// free seats to the line. One instance at a time (ShedLock); a missed run (the app asleep) is caught up by the next.
@Component
@RequiredArgsConstructor
public class WaitlistSweepJob {

	private final WaitlistOffers offers;

	@Scheduled(initialDelayString = "${waitlist.sweep.initial-delay}", fixedDelayString = "${waitlist.sweep.interval}")
	@SchedulerLock(name = "waitlistSweep", lockAtLeastFor = "PT20S")
	public void run() {
		offers.sweep();
	}
}

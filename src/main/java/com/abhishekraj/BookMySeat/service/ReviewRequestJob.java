package com.abhishekraj.BookMySeat.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Every 15 minutes (see ReviewRequests): asks the people who went to a show that is now over how it was.
// One instance at a time (ShedLock); a missed run (the app asleep) is caught up by the next.
@Component
@RequiredArgsConstructor
public class ReviewRequestJob {

	private final ReviewRequests requests;

	@Scheduled(initialDelayString = "${reviews.request.initial-delay}", fixedDelayString = "${reviews.request.interval}")
	@SchedulerLock(name = "reviewRequests", lockAtLeastFor = "PT20S")
	public void run() {
		requests.sendDue();
	}
}

package com.abhishekraj.BookMySeat.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration
public class ObservationConfig {

	// Render probes /actuator/health every few seconds: as traces those would bury the real requests.
	// (Also drops them from http.server.requests, which is fine: nobody charts the probes.)
	@Bean
	ObservationPredicate noActuatorObservations() {
		return (name, context) -> !(context instanceof ServerRequestObservationContext server
				&& server.getCarrier().getRequestURI().startsWith("/actuator"));
	}

	// The outbox and payment jobs poll every minute, mostly finding nothing: no trace per run. Database spans only
	// inside another trace, so their polling queries (and Flyway's at startup) aren't one-span traces either.
	// What a job does outside the app (a Brevo send, a Razorpay refund) still shows up as an HTTP client span.
	@Bean
	ObservationPredicate noIdleJobTraces(ObjectProvider<ObservationRegistry> registry) {
		return (name, context) -> {
			if (name.equals("tasks.scheduled.execution")) {
				return false;
			}
			if (name.startsWith("jdbc.")) {
				// Inside a switched-off job run the current observation is a no-op one: no parent span either
				Observation current = registry.getObject().getCurrentObservation();
				return current != null && !current.isNoop();
			}
			return true;
		};
	}
}

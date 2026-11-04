package com.abhishekraj.BookMySeat.config;

import io.micrometer.observation.ObservationPredicate;
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
}

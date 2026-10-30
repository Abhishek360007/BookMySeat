package com.abhishekraj.BookMySeat.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

// Gives every request an id: in the X-Request-Id response header, in every log line (MDC "requestId")
// and in every error body, so a reported error can be found in the logs. Runs before Spring Security,
// so 401/403 responses get an id too.
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";

	// A caller's id is reused only if it is short and harmless; anything else could pollute the logs
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(HEADER);
		String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
				? incoming : UUID.randomUUID().toString();

		MDC.put(MDC_KEY, requestId);
		response.setHeader(HEADER, requestId);
		long start = System.nanoTime();
		try {
			chain.doFilter(request, response);
		} finally {
			// Health probes run every few seconds on Render; logging them would drown everything else
			if (!request.getRequestURI().startsWith("/actuator/health")) {
				log.info("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(),
						response.getStatus(), (System.nanoTime() - start) / 1_000_000);
			}
			MDC.remove(MDC_KEY);
		}
	}

	/** The current request id, for error bodies written outside Spring MVC (security and rate-limit filters). */
	public static String current() {
		String id = MDC.get(MDC_KEY);
		return id != null ? id : "";
	}
}

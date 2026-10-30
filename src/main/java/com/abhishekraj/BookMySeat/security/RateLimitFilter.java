package com.abhishekraj.BookMySeat.security;

import com.abhishekraj.BookMySeat.config.RequestIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

// Per-IP limits on the public auth endpoints: slows password guessing and sign-up spam.
// The client IP is getRemoteAddr(); in prod, server.forward-headers-strategy makes that the
// address from Render's proxy (X-Forwarded-For).
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

	private record Rule(String name, int maxRequests, Duration window) {}

	private final RateLimiter rateLimiter;
	private final MeterRegistry meterRegistry;
	private final Map<String, Rule> rules;

	public RateLimitFilter(RateLimiter rateLimiter, MeterRegistry meterRegistry,
			@Value("${rate-limit.login.max-requests}") int loginMax,
			@Value("${rate-limit.login.window-seconds}") long loginWindow,
			@Value("${rate-limit.register.max-requests}") int registerMax,
			@Value("${rate-limit.register.window-seconds}") long registerWindow) {
		this.rateLimiter = rateLimiter;
		this.meterRegistry = meterRegistry;
		this.rules = Map.of(
				"/api/auth/login", new Rule("login", loginMax, Duration.ofSeconds(loginWindow)),
				"/api/auth/register", new Rule("register", registerMax, Duration.ofSeconds(registerWindow)));
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equals(request.getMethod()) || !rules.containsKey(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Rule rule = rules.get(request.getRequestURI());
		RateLimiter.Result result;
		try {
			result = rateLimiter.hit("rate:" + rule.name() + ":" + request.getRemoteAddr(),
					rule.maxRequests(), rule.window());
		} catch (RuntimeException ex) {
			// Fail open: a Redis outage must not lock everyone out of logging in
			log.warn("Rate limiter unavailable, allowing request: {}", ex.getMessage());
			chain.doFilter(request, response);
			return;
		}

		if (result.allowed()) {
			chain.doFilter(request, response);
			return;
		}

		meterRegistry.counter("rate_limit.rejected", "endpoint", rule.name()).increment();
		response.setStatus(429);
		response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
		response.setContentType("application/problem+json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("""
				{"title":"Too Many Requests","status":429,"detail":"Too many %s attempts. Try again in %d seconds.","instance":"%s","requestId":"%s"}
				""".formatted(rule.name(), result.retryAfterSeconds(), request.getRequestURI(), RequestIdFilter.current()));
	}
}

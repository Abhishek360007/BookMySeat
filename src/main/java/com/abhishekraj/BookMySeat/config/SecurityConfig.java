package com.abhishekraj.BookMySeat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

	/**
	 * Content-Security-Policy for the web app: scripts only from this site and Razorpay Checkout, never inline, and
	 * no other site may frame these pages (clickjacking). An injected script (XSS) therefore can't run, and data
	 * can't be sent anywhere but here and Razorpay. Inline styles are allowed only because Razorpay's checkout.js
	 * injects them (they can't run code); the app's own files have none (WebAppTest checks that).
	 */
	static final String CONTENT_SECURITY_POLICY = String.join("; ",
			"default-src 'self'",
			"script-src 'self' https://checkout.razorpay.com https://cdn.razorpay.com",
			"style-src 'self' 'unsafe-inline'",
			"img-src 'self' data: blob: https://*.razorpay.com", // blob: the e-ticket QR, fetched with the user's token
			"connect-src 'self' https://*.razorpay.com",
			"frame-src https://api.razorpay.com https://checkout.razorpay.com",
			"font-src 'self'",
			"object-src 'none'",
			"base-uri 'none'",
			"form-action 'self'",
			"frame-ancestors 'none'");

	// camera=(self): the admin check-in page scans tickets; no other site embedded here may use it
	static final String PERMISSIONS_POLICY = "camera=(self), microphone=(), geolocation=(), usb=()";

	/**
	 * The start page on GitHub Pages waits for the app to wake up (a free Render instance sleeps after 15 idle minutes)
	 * by reading the liveness probe. That one public GET is the only thing another site may read: every other path
	 * keeps the browser's same-origin rule.
	 */
	@Bean
	CorsConfigurationSource startPageCors(@Value("${app.start-page-origin}") String startPageOrigin) {
		CorsConfiguration liveness = new CorsConfiguration();
		liveness.setAllowedOrigins(List.of(startPageOrigin));
		liveness.setAllowedMethods(List.of("GET"));
		liveness.setMaxAge(600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/actuator/health/liveness", liveness);
		return source;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, CorsConfigurationSource startPageCors) throws Exception {
		http
			.cors(cors -> cors.configurationSource(startPageCors))
			// No cookies: the API authenticates with a Bearer header, which another site can't make a browser send
			.csrf(csrf -> csrf.disable())
			.headers(headers -> headers
				// Spring's defaults stay on: X-Content-Type-Options: nosniff, X-Frame-Options: DENY, HSTS on HTTPS,
				// Cache-Control: no-store
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
				.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY))
				// Swagger UI ships inline styles and scripts, so the policy covers everything but the docs
				.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
						new NegatedRequestMatcher(new OrRequestMatcher(
								PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui/**"),
								PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui.html"))),
						new StaticHeadersWriter("Content-Security-Policy", CONTENT_SECURITY_POLICY))))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/auth/**").permitAll()
				// The web app (static files served by this same server) and the settings it reads on startup
				.requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.svg", "/api/config").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll() // verified by its HMAC signature
				.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
				.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error").permitAll()
				.requestMatchers("/actuator/**").hasRole("ADMIN")
				.requestMatchers("/api/admin/**").hasRole("ADMIN")
				// Your own review of a show: any logged-in user (before the admin-only rule for the rest of /api/shows)
				.requestMatchers("/api/shows/*/reviews/me", "/api/films/*/reviews/me").authenticated()
				.requestMatchers(HttpMethod.GET, "/api/venues/**", "/api/shows/**", "/api/films/**").permitAll()
				.requestMatchers("/api/venues/**", "/api/shows/**").hasRole("ADMIN")
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2
				.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
				.authenticationEntryPoint(authenticationEntryPoint())
				.accessDeniedHandler(accessDeniedHandler()))
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(authenticationEntryPoint())
				.accessDeniedHandler(accessDeniedHandler()));
		return http.build();
	}

	// Reads the "roles" claim from our JWT and turns "ADMIN" into authority "ROLE_ADMIN"
	@Bean
	public JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("roles");
		authorities.setAuthorityPrefix("ROLE_");

		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}

	// 401: missing, expired or invalid token
	@Bean
	public AuthenticationEntryPoint authenticationEntryPoint() {
		return (request, response, ex) -> {
			response.setStatus(401);
			response.setHeader("WWW-Authenticate", "Bearer");
			response.setContentType("application/problem+json");
			response.getWriter().write("""
				{"title":"Unauthorized","status":401,"detail":"A valid Bearer token is required","instance":"%s","requestId":"%s"}
				""".formatted(request.getRequestURI(), RequestIdFilter.current()));
		};
	}

	// 403: valid token, but the role is not allowed
	@Bean
	public AccessDeniedHandler accessDeniedHandler() {
		return (request, response, ex) -> {
			response.setStatus(403);
			response.setContentType("application/problem+json");
			response.getWriter().write("""
				{"title":"Forbidden","status":403,"detail":"You do not have permission to do this","instance":"%s","requestId":"%s"}
				""".formatted(request.getRequestURI(), RequestIdFilter.current()));
		};
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}

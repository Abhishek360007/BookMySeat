package com.abhishekraj.BookMySeat.security;

import com.abhishekraj.BookMySeat.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class JwtService {

	private final JwtEncoder jwtEncoder;
	private final long expirationMinutes;
	private final String issuer;

	public JwtService(JwtEncoder jwtEncoder,
			@Value("${jwt.expiration-minutes}") long expirationMinutes,
			@Value("${jwt.issuer}") String issuer) {
		this.jwtEncoder = jwtEncoder;
		this.expirationMinutes = expirationMinutes;
		this.issuer = issuer;
	}

	public String generateToken(User user) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.subject(String.valueOf(user.getId()))
				.issuedAt(now)
				.expiresAt(now.plus(expirationMinutes, ChronoUnit.MINUTES))
				.claim("email", user.getEmail())
				.claim("roles", List.of(user.getRole().name()))
				.build();

		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	public long getExpirationSeconds() {
		return expirationMinutes * 60;
	}
}

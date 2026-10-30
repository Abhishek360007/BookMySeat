package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Show;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Locale;

// Composable search filters: each one is added only when the caller asked for it
public final class ShowSpecifications {

	private ShowSpecifications() {}

	public static Specification<Show> inCity(String city) {
		return (root, query, cb) -> cb.equal(cb.lower(root.get("venue").get("city")), city.trim().toLowerCase(Locale.ROOT));
	}

	public static Specification<Show> titleContains(String text) {
		String pattern = "%" + escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
		return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern, '\\');
	}

	public static Specification<Show> startsAtOrAfter(Instant from) {
		return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startTime"), from);
	}

	public static Specification<Show> startsBefore(Instant to) {
		return (root, query, cb) -> cb.lessThan(root.get("startTime"), to);
	}

	// A user typing "100%" should match the text "100%", not act as a wildcard
	private static String escapeLike(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}

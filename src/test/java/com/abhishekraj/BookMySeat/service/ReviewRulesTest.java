package com.abhishekraj.BookMySeat.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewRulesTest {

	static final Instant START = Instant.parse("2026-11-20T13:30:00Z");

	@Test
	void onlyPeopleWithABooking_onceTheShowHasStarted() {
		assertThat(ReviewRules.refusal(false, START, START.plusSeconds(3600))).contains(ReviewRules.Refusal.NO_BOOKING);
		assertThat(ReviewRules.refusal(false, START, START.minusSeconds(60))).contains(ReviewRules.Refusal.NO_BOOKING);
		assertThat(ReviewRules.refusal(true, START, START.minusSeconds(1))).contains(ReviewRules.Refusal.NOT_STARTED);
		assertThat(ReviewRules.refusal(true, START, START)).isEmpty(); // from the minute it starts
		assertThat(ReviewRules.refusal(true, START, START.plusSeconds(86_400 * 30))).isEmpty();
	}

	@Test
	void text_isTrimmed_withPlainLineBreaks_andNoControlCharacters() {
		assertThat(ReviewRules.text(null)).isNull();
		assertThat(ReviewRules.text("   \n\t ")).isNull();
		assertThat(ReviewRules.text("  Great show!  ")).isEqualTo("Great show!");
		assertThat(ReviewRules.text("one\r\ntwo\rthree")).isEqualTo("one\ntwo\nthree");
		assertThat(ReviewRules.text("a\n\n\n\n\n\nb")).isEqualTo("a\n\n\nb"); // at most two blank lines
		assertThat(ReviewRules.text("bell\u0007 and null\u0000 gone,\ttab kept")).isEqualTo("bell and null gone,\ttab kept");
		assertThat(ReviewRules.text("<b>₹ émojis 🎬</b>")).isEqualTo("<b>₹ émojis 🎬</b>"); // escaping is for display
	}

	@Test
	void average_hasOneDecimal_andIsNullWithoutRatings() {
		assertThat(ReviewRules.average(0, 0)).isNull();
		assertThat(ReviewRules.average(5, 1)).isEqualTo(5.0);
		assertThat(ReviewRules.average(13, 3)).isEqualTo(4.3);
		assertThat(ReviewRules.average(14, 3)).isEqualTo(4.7);
		assertThat(ReviewRules.average(7, 2)).isEqualTo(3.5);
	}

	@Test
	void displayName_isTheFirstNameAndAnInitial() {
		assertThat(ReviewRules.displayName("Asha Kumar")).isEqualTo("Asha K.");
		assertThat(ReviewRules.displayName("  Meera   S  iyer ")).isEqualTo("Meera I.");
		assertThat(ReviewRules.displayName("Ravi")).isEqualTo("Ravi");
		assertThat(ReviewRules.displayName("Zoë Ångström")).isEqualTo("Zoë Å.");
		assertThat(ReviewRules.displayName(" ")).isEqualTo("A guest");
		assertThat(ReviewRules.displayName(null)).isEqualTo("A guest");
	}
}

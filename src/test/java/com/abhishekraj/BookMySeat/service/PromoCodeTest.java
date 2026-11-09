package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.entity.PromoCode;
import com.abhishekraj.BookMySeat.entity.PromoKind;
import com.abhishekraj.BookMySeat.entity.Show;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

// The discount a code gives and the rules that need nothing but the code (no Spring, no database)
class PromoCodeTest {

	static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

	static PromoCode percent(String amount, String cap) {
		return new PromoCode("pct", null, PromoKind.PERCENT, new BigDecimal(amount), cap == null ? null : new BigDecimal(cap),
				null, null, NOW.minus(1, ChronoUnit.DAYS), null, null, 1, NOW);
	}

	static PromoCode flat(String amount) {
		return new PromoCode("flat", null, PromoKind.FLAT, new BigDecimal(amount), null, null, null,
				NOW.minus(1, ChronoUnit.DAYS), null, null, 1, NOW);
	}

	static BigDecimal rupees(String value) {
		return new BigDecimal(value);
	}

	@Test
	void codesAreStoredInCapitals_withoutSpaces() {
		assertThat(PromoCode.normalize("  diwali-25 ")).isEqualTo("DIWALI-25");
		assertThat(percent("10", null).getCode()).isEqualTo("PCT");
	}

	@Test
	void percentage_isRoundedToThePaisa_halfUp() {
		assertThat(percent("10", null).discountOn(rupees("450.00"))).isEqualByComparingTo("45.00");
		assertThat(percent("12.5", null).discountOn(rupees("199.99"))).isEqualByComparingTo("25.00"); // 24.99875
		assertThat(percent("33.33", null).discountOn(rupees("100.00"))).isEqualByComparingTo("33.33");
		assertThat(percent("10", null).discountOn(rupees("0.05"))).isEqualByComparingTo("0.00"); // nothing under ₹1
	}

	@Test
	void percentage_isCappedByMaxDiscount() {
		assertThat(percent("50", "100").discountOn(rupees("150.00"))).isEqualByComparingTo("75.00");
		assertThat(percent("50", "100").discountOn(rupees("600.00"))).isEqualByComparingTo("100.00");
	}

	@Test
	void discount_alwaysLeavesOneRupeeToPay() {
		assertThat(flat("150").discountOn(rupees("400.00"))).isEqualByComparingTo("150.00");
		assertThat(flat("500").discountOn(rupees("400.00"))).isEqualByComparingTo("399.00");
		assertThat(percent("100", null).discountOn(rupees("250.00"))).isEqualByComparingTo("249.00");
		assertThat(flat("50").discountOn(rupees("1.00"))).isEqualByComparingTo("0.00");
		assertThat(flat("50").discountOn(rupees("0.00"))).isEqualByComparingTo("0.00");
	}

	@Test
	void refusals_explainWhyACodeCantBeUsed() {
		Show show = new Show();
		show.setId(7L);
		Show other = new Show();
		other.setId(8L);

		assertThat(flat("50").refusal(NOW, 7L, rupees("200"))).isEmpty();

		PromoCode inactive = flat("50");
		inactive.setActive(false);
		assertThat(inactive.refusal(NOW, 7L, rupees("200"))).contains("This code is no longer active");

		PromoCode future = new PromoCode("SOON", null, PromoKind.FLAT, rupees("50"), null, null, null,
				NOW.plusSeconds(1), null, null, 1, NOW);
		assertThat(future.refusal(NOW, 7L, rupees("200"))).contains("This code isn't valid yet");

		// valid until is exclusive: refused exactly at that instant
		PromoCode ending = new PromoCode("ENDING", null, PromoKind.FLAT, rupees("50"), null, null, null,
				NOW.minusSeconds(60), NOW, null, 1, NOW);
		assertThat(ending.refusal(NOW.minusSeconds(1), 7L, rupees("200"))).isEmpty();
		assertThat(ending.refusal(NOW, 7L, rupees("200"))).contains("This code has expired");

		PromoCode forShow = new PromoCode("ONESHOW", null, PromoKind.FLAT, rupees("50"), null, null, show,
				NOW.minusSeconds(60), null, null, 1, NOW);
		assertThat(forShow.refusal(NOW, 7L, rupees("200"))).isEmpty();
		assertThat(forShow.refusal(NOW, other.getId(), rupees("200"))).contains("This code is for another show");

		PromoCode minimum = new PromoCode("MIN500", null, PromoKind.FLAT, rupees("50"), null, rupees("500.00"), null,
				NOW.minusSeconds(60), null, null, 1, NOW);
		assertThat(minimum.refusal(NOW, 7L, rupees("499.99"))).contains("This code needs a total of at least ₹500.00");
		assertThat(minimum.refusal(NOW, 7L, rupees("500.00"))).isEmpty();

		assertThat(flat("50").refusal(NOW, 7L, rupees("1.00"))).contains("This total is too small for a discount");
	}

	@Test
	void uses_stopAtTheLimit_andCanBeGivenBack() {
		PromoCode twice = new PromoCode("TWICE", null, PromoKind.FLAT, rupees("50"), null, null, null,
				NOW.minusSeconds(60), null, 2, 1, NOW);
		twice.use();
		twice.use();
		assertThat(twice.isUsedUp()).isTrue();
		assertThat(twice.refusal(NOW, 7L, rupees("200"))).contains("This code has been fully used");

		twice.release();
		assertThat(twice.getUses()).isEqualTo(1);
		assertThat(twice.refusal(NOW, 7L, rupees("200"))).isEmpty();

		PromoCode unlimited = flat("50");
		for (int i = 0; i < 1000; i++) {
			unlimited.use();
		}
		assertThat(unlimited.isUsedUp()).isFalse();
	}
}

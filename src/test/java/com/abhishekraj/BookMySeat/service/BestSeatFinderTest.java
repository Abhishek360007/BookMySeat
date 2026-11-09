package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.entity.SeatCategory;
import com.abhishekraj.BookMySeat.service.BestSeatFinder.SeatSpot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// The "pick the best seats for me" rules, on hand-made seat maps (no Spring, no database). Row A is the screen side.
class BestSeatFinderTest {

	/** A rows × perRow map, all free and regular. Seat id = row index × 100 + number, e.g. D5 = 305. */
	static List<SeatSpot> room(int rows, int perRow) {
		List<SeatSpot> seats = new ArrayList<>();
		for (int r = 0; r < rows; r++) {
			for (int n = 1; n <= perRow; n++) {
				seats.add(new SeatSpot(id(r, n), String.valueOf((char) ('A' + r)), n, SeatCategory.REGULAR, true));
			}
		}
		return seats;
	}

	static long id(int row, int number) {
		return row * 100L + number;
	}

	static long id(String seat) {
		return id(seat.charAt(0) - 'A', Integer.parseInt(seat.substring(1)));
	}

	static List<Long> ids(String... seats) {
		return java.util.Arrays.stream(seats).map(BestSeatFinderTest::id).toList();
	}

	static void taken(List<SeatSpot> seats, String... numbers) {
		Set<Long> gone = Set.copyOf(ids(numbers));
		seats.replaceAll(s -> gone.contains(s.id()) ? new SeatSpot(s.id(), s.row(), s.number(), s.category(), false) : s);
	}

	static void category(List<SeatSpot> seats, String row, SeatCategory category) {
		seats.replaceAll(s -> s.row().equals(row) ? new SeatSpot(s.id(), s.row(), s.number(), category, s.free()) : s);
	}

	static Optional<List<Long>> best(List<SeatSpot> seats, int count) {
		return BestSeatFinder.find(seats, Set.of(), count, null);
	}

	@Test
	void anEmptyRoom_givesTheMiddleOfTheRowAboutTwoThirdsBack() {
		// 5 rows: the sweet spot is between C and D (2.6), D is nearer
		assertThat(best(room(5, 10), 2)).contains(ids("D5", "D6"));
		assertThat(best(room(5, 9), 1)).contains(ids("D5"));
		// 3 seats can't be exactly central in a row of 10: the left of the two equal blocks, every time
		assertThat(best(room(5, 10), 3)).contains(ids("D4", "D5", "D6"));
	}

	@Test
	void takenSeats_areNeverPicked_andTheBlockStaysTogether() {
		List<SeatSpot> seats = room(5, 10);
		taken(seats, "D5");
		// D's best block would now be off-centre (D6, D7), so the middle of C wins
		assertThat(best(seats, 2)).contains(ids("C5", "C6"));

		taken(seats, "C5", "C6", "E1", "E10");
		assertThat(best(seats, 2).orElseThrow()).doesNotContainAnyElementsOf(ids("D5", "C5", "C6"));
	}

	@Test
	void aBlockNeverSpansAnAisle() {
		// Aisle after seat 5: D5 | D6 are not together
		Optional<List<Long>> picked = BestSeatFinder.find(room(5, 10), Set.of(5), 2, null);
		assertThat(picked).contains(ids("D4", "D5"));
		assertThat(BestSeatFinder.find(room(1, 10), Set.of(3, 7), 4, null)).contains(ids("A4", "A5", "A6", "A7"));
		assertThat(BestSeatFinder.find(room(1, 10), Set.of(3, 6), 4, null)).contains(ids("A7", "A8", "A9", "A10")); // the only run of 4
		assertThat(BestSeatFinder.find(room(1, 10), Set.of(3, 6, 8), 4, null)).isEmpty(); // longest run is 3
	}

	@Test
	void missingSeatNumbers_breakABlock() {
		List<SeatSpot> seats = new ArrayList<>(room(1, 6));
		seats.removeIf(s -> s.number() == 4); // A1-A3, A5-A6
		assertThat(best(seats, 3)).contains(ids("A1", "A2", "A3"));
		assertThat(best(seats, 4)).isEmpty();
	}

	@Test
	void aCategory_isRespected_andABlockNeverMixesCategories() {
		List<SeatSpot> seats = room(5, 10);
		category(seats, "E", SeatCategory.RECLINER);
		assertThat(BestSeatFinder.find(seats, Set.of(), 2, SeatCategory.RECLINER)).contains(ids("E5", "E6"));
		assertThat(BestSeatFinder.find(seats, Set.of(), 2, SeatCategory.PREMIUM)).isEmpty();
		assertThat(BestSeatFinder.find(seats, Set.of(), 2, SeatCategory.REGULAR)).contains(ids("D5", "D6"));

		// D5 regular, D6 premium: not one block when no category is asked for either
		List<SeatSpot> mixed = room(5, 10);
		mixed.replaceAll(s -> s.row().equals("D") && s.number() > 5
				? new SeatSpot(s.id(), s.row(), s.number(), SeatCategory.PREMIUM, true) : s);
		assertThat(best(mixed, 2)).contains(ids("C5", "C6"));
	}

	@Test
	void tooManySeats_orNoneAsked_findsNothing() {
		assertThat(best(room(5, 10), 11)).isEmpty();
		assertThat(best(room(5, 10), 0)).isEmpty();
		assertThat(best(List.of(), 2)).isEmpty();
		List<SeatSpot> full = room(2, 4);
		taken(full, "A2", "B3");
		assertThat(best(full, 3)).isEmpty(); // runs of 1 and 2 only
	}

	@Test
	void oneRow_andRowsBeyondZ_work() {
		assertThat(best(room(1, 5), 2)).contains(ids("A2", "A3"));
		// Rows "AA" come after "Z", not between "A" and "B"
		List<SeatSpot> seats = new ArrayList<>(List.of(
				new SeatSpot(1L, "A", 1, SeatCategory.REGULAR, false),
				new SeatSpot(2L, "B", 1, SeatCategory.REGULAR, true),
				new SeatSpot(3L, "AA", 1, SeatCategory.REGULAR, true)));
		// 3 rows: sweet spot 1.3, so B (index 1) beats AA (index 2)
		assertThat(best(seats, 1)).contains(List.of(2L));
	}

	@Test
	void theAnswer_doesNotDependOnTheOrderOfTheSeats() {
		List<SeatSpot> seats = room(8, 14);
		taken(seats, "F7", "F8", "E3", "G10", "D7");
		List<Long> expected = best(seats, 4).orElseThrow();
		Random random = new Random(42);
		for (int i = 0; i < 20; i++) {
			Collections.shuffle(seats, random);
			assertThat(best(seats, 4)).contains(expected);
		}
	}
}

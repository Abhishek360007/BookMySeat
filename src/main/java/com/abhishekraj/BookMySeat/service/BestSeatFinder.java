package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.entity.SeatCategory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Picks the best block of seats next to each other: plain Java, no Spring or database, so every rule is unit-tested.
 *
 * <p>A block is `count` seats in one row with consecutive numbers, all free, all of one category (the asked-for one,
 * if any), and no aisle between them. The best block is the one nearest the middle of its row and nearest the
 * "sweet spot" row about two thirds of the way back from the screen (row A is the front). Ties go to the row nearer
 * the front, then to the leftmost block, so the same seat map always gives the same answer.
 */
public final class BestSeatFinder {

	/** One seat of the map. free: AVAILABLE and held by nobody else. */
	public record SeatSpot(Long id, String row, int number, SeatCategory category, boolean free) {}

	/** Where the sweet spot is: this fraction of the way from the front row (0) to the back row (1). */
	static final double SWEET_SPOT = 0.65;
	/** Being off-centre sideways costs more than being a row too near or too far. */
	static final double SIDEWAYS_WEIGHT = 1.0;
	static final double ROW_WEIGHT = 0.8;

	private BestSeatFinder() {
	}

	/**
	 * The ids of the best `count` seats together, left to right, or empty when no row has that many free seats
	 * together (in that category).
	 */
	public static Optional<List<Long>> find(Collection<SeatSpot> seats, Set<Integer> aisleAfter, int count,
			SeatCategory category) {
		if (count < 1 || seats.isEmpty()) {
			return Optional.empty();
		}
		// Rows in order from the screen; every seat counts for the shape of the room, free or not
		Map<String, List<SeatSpot>> rows = new TreeMap<>(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
		for (SeatSpot seat : seats) {
			rows.computeIfAbsent(seat.row(), r -> new ArrayList<>()).add(seat);
		}
		int lastRow = rows.size() - 1;
		double sweetRow = lastRow * SWEET_SPOT;

		Block best = null;
		int rowIndex = 0;
		for (List<SeatSpot> row : rows.values()) {
			row.sort(Comparator.comparingInt(SeatSpot::number));
			double rowMiddle = (row.getFirst().number() + row.getLast().number()) / 2.0;
			double halfWidth = Math.max((row.getLast().number() - row.getFirst().number()) / 2.0, 1);
			double rowCost = lastRow == 0 ? 0 : Math.abs(rowIndex - sweetRow) / lastRow;
			for (int start = 0; start + count <= row.size(); start++) {
				List<SeatSpot> block = row.subList(start, start + count);
				if (!fits(block, aisleAfter, category)) {
					continue;
				}
				double blockMiddle = (block.getFirst().number() + block.getLast().number()) / 2.0;
				double cost = SIDEWAYS_WEIGHT * Math.abs(blockMiddle - rowMiddle) / halfWidth + ROW_WEIGHT * rowCost;
				Block candidate = new Block(block, cost, rowIndex, block.getFirst().number());
				if (best == null || candidate.isBetterThan(best)) {
					best = candidate;
				}
			}
			rowIndex++;
		}
		return Optional.ofNullable(best).map(b -> b.seats().stream().map(SeatSpot::id).toList());
	}

	private static boolean fits(List<SeatSpot> block, Set<Integer> aisleAfter, SeatCategory category) {
		SeatCategory first = block.getFirst().category();
		if (category != null && first != category) {
			return false;
		}
		for (int i = 0; i < block.size(); i++) {
			SeatSpot seat = block.get(i);
			if (!seat.free() || seat.category() != first) {
				return false;
			}
			if (i > 0) {
				SeatSpot previous = block.get(i - 1);
				// Next to each other: no missing seat number, and no aisle in between
				if (seat.number() != previous.number() + 1 || aisleAfter.contains(previous.number())) {
					return false;
				}
			}
		}
		return true;
	}

	private record Block(List<SeatSpot> seats, double cost, int row, int firstNumber) {

		// Costs closer than this are a tie (floating point), decided by row and then position
		private static final double TIE = 1e-9;

		boolean isBetterThan(Block other) {
			if (Math.abs(cost - other.cost) > TIE) {
				return cost < other.cost;
			}
			if (row != other.row) {
				return row < other.row;
			}
			return firstNumber < other.firstNumber;
		}
	}
}

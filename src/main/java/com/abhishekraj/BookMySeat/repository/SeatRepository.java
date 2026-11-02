package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {
	List<Seat> findByShowIdOrderByIdAsc(Long showId);
	boolean existsByShowId(Long showId);

	@Query("""
			select new com.abhishekraj.BookMySeat.repository.ShowStats$Seats(s.show.id, count(s),
			    sum(case when s.status = com.abhishekraj.BookMySeat.entity.SeatStatus.BOOKED then 1L else 0L end))
			from Seat s where s.show.id in :showIds group by s.show.id""")
	List<ShowStats.Seats> countByShow(Collection<Long> showIds);

	@Query("""
			select new com.abhishekraj.BookMySeat.repository.ShowStats$SeatTotals(count(s),
			    coalesce(sum(case when s.status = com.abhishekraj.BookMySeat.entity.SeatStatus.BOOKED then 1L else 0L end), 0L))
			from Seat s where s.show.startTime > :now""")
	ShowStats.SeatTotals countUpcoming(Instant now);
}

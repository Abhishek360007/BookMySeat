package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.ShowRequest;
import com.abhishekraj.BookMySeat.dto.ShowResponse;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.Venue;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ShowService {

	private final ShowRepository showRepository;
	private final VenueRepository venueRepository;
	private final SeatRepository seatRepository;

	@Transactional
	public ShowResponse create(ShowRequest request) {
		Show show = new Show();
		applyRequest(show, request);
		return ShowResponse.from(showRepository.save(show));
	}

	@Transactional(readOnly = true)
	public List<ShowResponse> getAll(Long venueId) {
		List<Show> shows = (venueId == null)
				? showRepository.findAll()
				: showRepository.findByVenueId(venueId);
		return shows.stream().map(ShowResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public ShowResponse getById(Long id) {
		return ShowResponse.from(findShow(id));
	}

	@Transactional
	public ShowResponse update(Long id, ShowRequest request) {
		Show show = findShow(id);
		applyRequest(show, request);
		return ShowResponse.from(show);
	}

	@Transactional
	public void delete(Long id) {
		Show show = findShow(id);
		if (seatRepository.existsByShowId(id)) {
			throw new ConflictException("Cannot delete show " + id + " because it has seats.");
		}
		showRepository.delete(show);
	}

	private void applyRequest(Show show, ShowRequest request) {
		Venue venue = venueRepository.findById(request.venueId())
				.orElseThrow(() -> new ResourceNotFoundException("Venue not found with id " + request.venueId()));
		show.setTitle(request.title());
		show.setStartTime(request.startTime());
		show.setPrice(request.price());
		show.setVenue(venue);
	}

	private Show findShow(Long id) {
		return showRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + id));
	}
}

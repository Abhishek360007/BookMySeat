package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.VenueRequest;
import com.abhishekraj.BookMySeat.dto.VenueResponse;
import com.abhishekraj.BookMySeat.entity.Venue;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VenueService {

	private final VenueRepository venueRepository;

	@Transactional
	public VenueResponse create(VenueRequest request) {
		Venue venue = new Venue();
		venue.setName(request.name());
		venue.setCity(request.city());
		venue.setAddress(request.address());
		return VenueResponse.from(venueRepository.save(venue));
	}

	@Transactional(readOnly = true)
	public List<VenueResponse> getAll() {
		return venueRepository.findAll().stream().map(VenueResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public VenueResponse getById(Long id) {
		return VenueResponse.from(findVenue(id));
	}

	@Transactional
	public VenueResponse update(Long id, VenueRequest request) {
		Venue venue = findVenue(id);
		venue.setName(request.name());
		venue.setCity(request.city());
		venue.setAddress(request.address());
		return VenueResponse.from(venue);
	}

	@Transactional
	public void delete(Long id) {
		venueRepository.delete(findVenue(id));
	}

	private Venue findVenue(Long id) {
		return venueRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Venue not found with id " + id));
	}
}

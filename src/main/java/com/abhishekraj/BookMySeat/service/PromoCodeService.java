package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.dto.PromoCodeRequest;
import com.abhishekraj.BookMySeat.dto.PromoCodeResponse;
import com.abhishekraj.BookMySeat.dto.PromoPreviewRequest;
import com.abhishekraj.BookMySeat.dto.PromoPreviewResponse;
import com.abhishekraj.BookMySeat.entity.PromoCode;
import com.abhishekraj.BookMySeat.entity.PromoKind;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.PromoCodeRefusedException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.PromoCodeRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class PromoCodeService {

	private final PromoCodeRepository promoCodeRepository;
	private final ShowRepository showRepository;
	private final SeatRepository seatRepository;
	private final Clock clock;

	public PromoCodeService(PromoCodeRepository promoCodeRepository, ShowRepository showRepository,
			SeatRepository seatRepository, Clock clock) {
		this.promoCodeRepository = promoCodeRepository;
		this.showRepository = showRepository;
		this.seatRepository = seatRepository;
		this.clock = clock;
	}

	@Transactional
	public PromoCodeResponse create(PromoCodeRequest request) {
		Instant now = Instant.now(clock);
		String code = PromoCode.normalize(request.code());
		if (request.kind() == PromoKind.PERCENT && request.amount().compareTo(new BigDecimal("100")) > 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A percentage can't be more than 100");
		}
		if (request.kind() == PromoKind.FLAT && request.maxDiscount() != null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A cap (maxDiscount) only applies to a percentage");
		}
		Instant validFrom = request.validFrom() != null ? request.validFrom() : now;
		if (request.validUntil() != null && !request.validUntil().isAfter(validFrom)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "validUntil must be after validFrom");
		}
		Show show = null;
		if (request.showId() != null) {
			show = showRepository.findById(request.showId())
					.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + request.showId()));
		}
		if (promoCodeRepository.existsByCode(code)) {
			throw new ConflictException("Code " + code + " already exists"); // the unique key catches a race
		}
		int perUser = request.maxUsesPerUser() != null ? request.maxUsesPerUser() : 1;
		PromoCode promo = promoCodeRepository.save(new PromoCode(code, blankToNull(request.description()),
				request.kind(), request.amount(), request.maxDiscount(), request.minTotal(), show, validFrom,
				request.validUntil(), request.maxUses(), perUser, now));
		return PromoCodeResponse.from(promo);
	}

	@Transactional(readOnly = true)
	public PageResponse<PromoCodeResponse> list(Pageable pageable) {
		return PageResponse.of(promoCodeRepository.findAll(pageable), PromoCodeResponse::from);
	}

	@Transactional
	public PromoCodeResponse setActive(Long id, boolean active) {
		PromoCode promo = promoCodeRepository.findForUpdateById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Promo code not found with id " + id));
		promo.setActive(active);
		return PromoCodeResponse.from(promo);
	}

	/** The price of these seats with the code, without using it up. */
	@Transactional(readOnly = true)
	public PromoPreviewResponse preview(Long userId, PromoPreviewRequest request) {
		Show show = showRepository.findWithPricesById(request.showId())
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + request.showId()));
		List<Long> seatIds = request.seatIds().stream().distinct().toList();
		List<Seat> seats = seatRepository.findAllById(seatIds);
		if (seats.size() != seatIds.size() || !seats.stream().allMatch(seat -> seat.getShow().getId().equals(show.getId()))) {
			throw new ResourceNotFoundException("One or more seats were not found for show " + show.getId());
		}
		BigDecimal subtotal = show.totalFor(seats);
		PromoCode promo = promoCodeRepository.findByCode(PromoCode.normalize(request.code()))
				.orElseThrow(() -> new PromoCodeRefusedException("This code doesn't exist"));
		promo.refusal(Instant.now(clock), show.getId(), subtotal).ifPresent(reason -> {
			throw new PromoCodeRefusedException(reason);
		});
		BigDecimal discount = promo.discountOn(subtotal);
		return new PromoPreviewResponse(promo.getCode(), promo.getDescription(), subtotal, discount,
				subtotal.subtract(discount));
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}
}

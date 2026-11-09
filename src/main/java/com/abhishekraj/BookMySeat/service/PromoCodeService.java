package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.dto.PromoCodeRequest;
import com.abhishekraj.BookMySeat.dto.PromoCodeResponse;
import com.abhishekraj.BookMySeat.dto.PromoPreviewRequest;
import com.abhishekraj.BookMySeat.dto.PromoPreviewResponse;
import com.abhishekraj.BookMySeat.entity.Booking;
import com.abhishekraj.BookMySeat.entity.PromoCode;
import com.abhishekraj.BookMySeat.entity.PromoKind;
import com.abhishekraj.BookMySeat.entity.PromoRedemption;
import com.abhishekraj.BookMySeat.entity.Seat;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.PromoCodeRefusedException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.PromoCodeRepository;
import com.abhishekraj.BookMySeat.repository.PromoRedemptionRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.support.AfterCommit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Promo codes: what admins manage, the price preview, and using a code on a booking. A use is counted on the code's
 * row while that row is locked (FOR UPDATE), so bookings with the same code take turns and neither the total limit
 * nor the per-person limit can be overrun by bookings at the same moment. A cancelled booking gives its use back.
 */
@Service
public class PromoCodeService {

	/** A code taken for one booking: the locked code and what it takes off. */
	public record Use(PromoCode code, BigDecimal discount) {}

	private final PromoCodeRepository promoCodeRepository;
	private final PromoRedemptionRepository redemptionRepository;
	private final ShowRepository showRepository;
	private final SeatRepository seatRepository;
	private final UserRepository userRepository;
	private final MeterRegistry meterRegistry;
	private final Clock clock;

	public PromoCodeService(PromoCodeRepository promoCodeRepository, PromoRedemptionRepository redemptionRepository,
			ShowRepository showRepository, SeatRepository seatRepository, UserRepository userRepository,
			MeterRegistry meterRegistry, Clock clock) {
		this.promoCodeRepository = promoCodeRepository;
		this.redemptionRepository = redemptionRepository;
		this.showRepository = showRepository;
		this.seatRepository = seatRepository;
		this.userRepository = userRepository;
		this.meterRegistry = meterRegistry;
		this.clock = clock;
		for (String result : new String[] {"redeemed", "refused", "released"}) {
			meterRegistry.counter("promo_codes.uses", "result", result); // at 0 from startup, see BookingMetrics
		}
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
		check(promo, userId, show.getId(), subtotal);
		BigDecimal discount = promo.discountOn(subtotal);
		return new PromoPreviewResponse(promo.getCode(), promo.getDescription(), subtotal, discount,
				subtotal.subtract(discount));
	}

	/**
	 * Takes one use of the code for a booking of `subtotal`, inside the booking's transaction (the row lock is held
	 * until it commits; a rollback gives the use back by itself). Throws PromoCodeRefusedException when it can't be used.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Use use(Long userId, String rawCode, Long showId, BigDecimal subtotal) {
		PromoCode promo = promoCodeRepository.findForUpdateByCode(PromoCode.normalize(rawCode)).orElse(null);
		try {
			if (promo == null) {
				throw new PromoCodeRefusedException("This code doesn't exist");
			}
			check(promo, userId, showId, subtotal);
		} catch (PromoCodeRefusedException ex) {
			meterRegistry.counter("promo_codes.uses", "result", "refused").increment();
			throw ex;
		}
		promo.use();
		return new Use(promo, promo.discountOn(subtotal));
	}

	/** Records the use on the booking it was taken for (same transaction as use()). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void redeemed(Use use, Long userId, Booking booking) {
		redemptionRepository.save(new PromoRedemption(use.code(), userRepository.getReferenceById(userId), booking,
				Instant.now(clock)));
		AfterCommit.run(() -> meterRegistry.counter("promo_codes.uses", "result", "redeemed").increment());
	}

	/** A cancelled booking (by the customer or with its show) gives its use back, in the cancel's transaction. */
	@EventListener
	public void onBookingCancelled(BookingCancelledEvent event) {
		redemptionRepository.findByBookingIdAndStatus(event.bookingId(), PromoRedemption.Status.REDEEMED)
				.ifPresent(redemption -> {
					promoCodeRepository.findForUpdateById(redemption.getPromoCode().getId()).ifPresent(PromoCode::release);
					redemption.release(Instant.now(clock));
					AfterCommit.run(() -> meterRegistry.counter("promo_codes.uses", "result", "released").increment());
				});
	}

	private void check(PromoCode promo, Long userId, Long showId, BigDecimal subtotal) {
		promo.refusal(Instant.now(clock), showId, subtotal).ifPresent(reason -> {
			throw new PromoCodeRefusedException(reason);
		});
		long mine = redemptionRepository.countByPromoCodeIdAndUserIdAndStatus(promo.getId(), userId,
				PromoRedemption.Status.REDEEMED);
		if (mine >= promo.getMaxUsesPerUser()) {
			throw new PromoCodeRefusedException(promo.getMaxUsesPerUser() == 1
					? "You've already used this code"
					: "You've already used this code " + promo.getMaxUsesPerUser() + " times");
		}
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}
}

package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.PromoRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface PromoRedemptionRepository extends JpaRepository<PromoRedemption, Long> {

	long countByPromoCodeIdAndUserIdAndStatusIn(Long promoCodeId, Long userId, Collection<PromoRedemption.Status> statuses);

	Optional<PromoRedemption> findByBookingIdAndStatus(Long bookingId, PromoRedemption.Status status);

	Optional<PromoRedemption> findByPaymentId(Long paymentId);
}

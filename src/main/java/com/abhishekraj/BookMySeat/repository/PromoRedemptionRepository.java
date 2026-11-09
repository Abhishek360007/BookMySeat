package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.PromoRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PromoRedemptionRepository extends JpaRepository<PromoRedemption, Long> {

	long countByPromoCodeIdAndUserIdAndStatus(Long promoCodeId, Long userId, PromoRedemption.Status status);

	Optional<PromoRedemption> findByBookingIdAndStatus(Long bookingId, PromoRedemption.Status status);
}

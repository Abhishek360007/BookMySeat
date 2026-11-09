package com.abhishekraj.BookMySeat.repository;

import com.abhishekraj.BookMySeat.entity.PromoCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PromoCodeRepository extends JpaRepository<PromoCode, Long> {

	Optional<PromoCode> findByCode(String code);

	boolean existsByCode(String code);

	// FOR UPDATE: bookings using the same code take turns, so its limits can't be overrun by a race
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from PromoCode p where p.code = :code")
	Optional<PromoCode> findForUpdateByCode(String code);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from PromoCode p where p.id = :id")
	Optional<PromoCode> findForUpdateById(Long id);

	@Override
	@EntityGraph(attributePaths = "show")
	Page<PromoCode> findAll(Pageable pageable);
}

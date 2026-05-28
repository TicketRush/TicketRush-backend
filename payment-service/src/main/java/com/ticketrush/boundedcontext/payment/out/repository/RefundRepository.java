package com.ticketrush.boundedcontext.payment.out.repository;

import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

  Optional<Refund> findByBookingId(Long bookingId);
}

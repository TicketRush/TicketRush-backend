package com.ticketrush.boundedcontext.payment.out.repository;

import com.ticketrush.boundedcontext.payment.domain.entity.ExpiredBooking;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpiredBookingRepository extends JpaRepository<ExpiredBooking, Long> {

  boolean existsByBookingId(Long bookingId);
}

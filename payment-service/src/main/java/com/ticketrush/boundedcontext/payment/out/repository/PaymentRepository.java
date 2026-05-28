package com.ticketrush.boundedcontext.payment.out.repository;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

  boolean existsByBookingIdAndStatus(Long bookingId, PaymentStatus status);

  Page<Payment> findByUserIdAndStatus(Long userId, PaymentStatus status, Pageable pageable);

  Optional<Payment> findByIdAndUserId(Long id, Long userId);
}

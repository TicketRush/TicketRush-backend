package com.ticketrush.boundedcontext.payment.out.repository;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

  boolean existsByBookingIdAndStatus(Long bookingId, PaymentStatus status);

  /*
   * 멱등 fallback에서 "먼저 확정된 COMPLETED 결제"를 조회한다. uk_payment_completed_booking 제약이 정상 적용된 환경에서는
   * booking당 COMPLETED가 최대 1건이지만, 제약이 누락된 비정상 환경에서도 IncorrectResultSizeDataAccessException으로
   * 깨지지 않도록 findFirst(LIMIT 1)로 조회한다(#296).
   */
  Optional<Payment> findFirstByBookingIdAndStatus(Long bookingId, PaymentStatus status);

  Page<Payment> findByUserIdAndStatus(Long userId, PaymentStatus status, Pageable pageable);

  Optional<Payment> findByIdAndUserId(Long id, Long userId);

  Optional<Payment> findByPaymentKey(String paymentKey);
}

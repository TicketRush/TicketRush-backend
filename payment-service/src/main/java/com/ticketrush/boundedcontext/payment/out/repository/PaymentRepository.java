package com.ticketrush.boundedcontext.payment.out.repository;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

  boolean existsByBookingIdAndStatus(Long bookingId, PaymentStatus status);

  /*
   * 멱등 fallback에서 "먼저 확정된 COMPLETED 결제"를 조회한다. uk_payment_completed_booking 제약이 정상 적용된 환경에서는
   * booking당 COMPLETED가 최대 1건이지만, 제약이 누락된 비정상 환경에서도 IncorrectResultSizeDataAccessException으로
   * 깨지지 않도록 findFirst(LIMIT 1)로 조회한다(#296). 실제로 스키마 스냅샷에서 제약이 누락된 채 초기화될 수 있었던
   * 사례가 있었고(#422) validate·CI 모두 unique 부재를 검출하지 못하므로, 이 방어는 유지한다.
   */
  Optional<Payment> findFirstByBookingIdAndStatus(Long bookingId, PaymentStatus status);

  Page<Payment> findByUserIdAndStatus(Long userId, PaymentStatus status, Pageable pageable);

  Optional<Payment> findByIdAndUserId(Long id, Long userId);

  Optional<Payment> findByPaymentKey(String paymentKey);

  /*
   * booking당 FAILED 이력 누적 상한을 판정하기 위해 현재 개수를 센다(#333). FAILED row는
   * payment_key·completed_booking_id가 NULL이라 어떤 unique 제약에도 걸리지 않아 동일 booking에 무제한 누적될 수
   * 있으므로, 기록 직전 이 개수로 상한을 건다.
   */
  long countByBookingIdAndStatus(Long bookingId, PaymentStatus status);

  /*
   * 만료 예매 후보 묶음에 대해 해당 상태의 결제를 한 번에 대조한다(#607). 후보를 한 건씩 조회하면 배치 크기만큼
   * 왕복이 늘고, 조인 한 방으로 합치면 희소 매치에서 조기 종료가 안 돼 매 주기 풀스캔이 된다 —
   * ExpiredBookingRepository.findByIdGreaterThanOrderByIdAsc 의 javadoc 참고.
   * idx_payment_booking_id(#412)를 타며, booking당 COMPLETED 는 unique 제약상 최대 1건이다(#296).
   */
  List<Payment> findByBookingIdInAndStatus(Collection<Long> bookingIds, PaymentStatus status);
}

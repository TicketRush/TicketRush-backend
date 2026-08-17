package com.ticketrush.boundedcontext.payment.out.repository;

import com.ticketrush.boundedcontext.payment.domain.entity.ExpiredBooking;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpiredBookingRepository extends JpaRepository<ExpiredBooking, Long> {

  boolean existsByBookingId(Long bookingId);

  /**
   * 만료 예매를 {@code idAfter} 다음부터 PK 오름차순으로 조회한다 (#607).
   *
   * <p><b>결제 상태를 조인하지 않는다.</b> "과금됐는데 만료된" 건은 전체 만료 중 극히 드물어서, 조인 + LIMIT 한 방으로 짜면 옵티마이저가 상한만큼 채우려고
   * 매치를 찾을 때까지 스캔을 이어가 <b>매 주기 사실상 풀스캔</b>이 된다(ADR 0014가 {@code idx_refund_status}에서 겪은 것과 같은 형태).
   * 이 조회로 PK 범위만 끊고, 결제 대조는 {@code booking_id IN (...)} 2단계 질의로 분리한다.
   *
   * <p>{@code idAfter}(커서)가 필요한 이유는 상한만 두고 매번 앞에서 읽으면 만료 건수가 상한을 넘는 순간 뒤쪽이 영영 선택되지 않기 때문이다. {@code
   * expired_booking}은 해결돼도 행이 사라지지 않아 대상 집합이 스스로 줄지 않으므로, 커서 없이는 뒤쪽 사고 건이 <b>영구히</b> 묻힌다.
   */
  List<ExpiredBooking> findByIdGreaterThanOrderByIdAsc(Long idAfter, Pageable pageable);
}

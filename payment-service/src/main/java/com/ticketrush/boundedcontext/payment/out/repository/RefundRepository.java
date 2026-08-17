package com.ticketrush.boundedcontext.payment.out.repository;

import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

  Optional<Refund> findByBookingId(Long bookingId);

  Optional<Refund> findByPaymentId(Long paymentId);

  /**
   * 해당 상태의 환불 이력을 {@code idAfter} 다음부터 오래된 순으로 조회한다 (#574).
   *
   * <p>{@code idx_refund_status}를 타며, 같은 status 안에서는 InnoDB가 PK 순으로 담으므로 별도 정렬 없이 refund_id 오름차순이
   * 나온다. 한 번에 다 가져오지 않도록 호출자가 {@code Pageable}로 상한을 준다.
   *
   * <p><b>{@code idAfter}(커서)가 필요한 이유</b>는 상한만 두고 매번 가장 오래된 것부터 읽으면 대상이 상한을 넘는 순간 뒤쪽 건이 영영 선택되지 않기
   * 때문이다. FAILED 이력은 재환불이 성공해야 빠지는데, 그 재환불을 여는 것이 이 조회가 촉발하는 재발행이라 순환이 끊긴다.
   */
  List<Refund> findByStatusAndIdGreaterThanOrderByIdAsc(
      RefundStatus status, Long idAfter, Pageable pageable);

  long countByStatus(RefundStatus status);

  /**
   * 여러 예매의 환불 이력을 한 번에 가져온다 (#607).
   *
   * <p>과금-만료 복구가 PG 취소를 호출하기 전에 거는 선검사다. 상태를 가리지 않고 조회하는 것은 의도적이다 — FAILED 이력도 "이미 시도했다"는 뜻이라 다시
   * 집어들면 실패한 환불을 매 주기 PG 에 되풀이해 던진다. 다만 호출자는 성사된 건과 FAILED 만 남은 건을 <b>관측에서는 갈라야 한다.</b> 후자는 돈이 돌아가지
   * 않은 미해결 사고이고, 예매가 EXPIRED 라 booking 쪽 관리자 게이트도 열리지 않기 때문이다.
   *
   * <p><b>{@code IN} 으로 묶어 받는 이유</b>는 {@code refund} 에 {@code booking_id} 인덱스가 없기 때문이다(키는 PK ·
   * {@code UNIQUE(payment_id)} · {@code idx_refund_status} 뿐). 건별로 조회하면 호출마다 풀스캔이고, 이 테이블은 성공한 환불까지
   * 누적돼 계속 커진다. 묶으면 풀스캔이 랩당 1 회로 줄어 <b>인덱스를 새로 만들지 않고도</b> 비용이 후보 수에 비례하지 않는다.
   *
   * <p><b>이 검사는 PG 호출 억제용이지 정합성 근거가 아니다.</b> 조회와 취소 사이의 경합은 막지 못한다. 이중 환불을 실제로 막는 것은 PG
   * 멱등키(paymentId 고정)·{@code refund.payment_id} unique·{@code Payment#markCanceled}의 상태 가드 셋이다.
   */
  List<Refund> findByBookingIdIn(Collection<Long> bookingIds);
}

package com.ticketrush.boundedcontext.payment.out.repository;

import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
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
}

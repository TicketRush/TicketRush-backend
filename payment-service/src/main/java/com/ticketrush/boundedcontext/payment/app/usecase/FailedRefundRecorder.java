package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * PG 환불 거절 시 {@code RefundStatus.FAILED} 환불 이력을 남기는 공유 컴포넌트(#334).
 *
 * <p>이벤트 경로({@link PaymentRefundByBookingUseCase}, #91)와 API 취소 경로({@link PaymentCancelUseCase},
 * #22)가 동일한 실패 기록 로직을 공유하므로 별도 빈으로 분리했다. 저장 자체({@link PaymentCancelPersister#persistFailedRefund})는
 * {@code @Transactional}이라, 그 {@code DataIntegrityViolationException}을 트랜잭션 경계 밖에서 잡으려면 호출부가 별도
 * 빈이어야 한다(persister self-invocation 프록시 한계와 동일 이유).
 *
 * <p>기록은 <b>PG 거절({@link ErrorStatus#PAYMENT_REFUND_FAILED}, 결정적 실패)일 때만</b> 수행한다. PG 통신 실패(성공 여부
 * 불명)는 재시도→DLT로 넘겨야 하므로 FAILED로 확정 기록하지 않는다(리스너 분류와 동일 기준). 저장 실패는 원래의 환불 실패 예외(보상 흐름)를 가리지 않도록
 * 삼킨다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailedRefundRecorder {

  private final PaymentCancelPersister paymentCancelPersister;

  /**
   * PG 환불 실패 중 "결정적 거절"(FAILED 기록·보상 대상)인지 판별하는 단일 기준(SSOT).
   *
   * <p>통신 실패({@link ErrorStatus#PAYMENT_PG_COMMUNICATION_FAILED}, 성공 여부 불명 → 재시도)와 구분한다. FAILED 이력
   * 기록(본 컴포넌트)과 보상 이벤트 발행({@link
   * com.ticketrush.boundedcontext.payment.in.eventlistener.RefundRequestedEventListener})이 반드시 같은
   * 기준으로 짝을 맞춰야 하므로, 두 곳이 각각 하드코딩하지 않고 이 술어를 공유한다.
   */
  public static boolean isDeterministicRejection(BusinessException e) {
    return e.getErrorStatus() == ErrorStatus.PAYMENT_REFUND_FAILED;
  }

  /**
   * PG 거절로 확정된 실패면 FAILED 환불 이력을 남긴다. 그 외(통신 실패 등)는 남기지 않는다.
   *
   * <p>부수효과일 뿐이므로 호출자는 이 메서드 호출 후 원 예외를 그대로 재던져 #91 보상/재시도 흐름을 유지한다.
   *
   * <p><b>불변식</b>: 호출자({@code UseCase.execute})는 트랜잭션 밖이어야 한다. 그래야 {@code
   * persistFailedRefund}(REQUIRED)가 독립 트랜잭션으로 커밋돼, 호출자가 원 예외를 재던져도 FAILED 이력이 함께 롤백되지 않는다. execute를
   * 트랜잭션으로 감싸면 이 보증이 깨진다(#297 동일 전제).
   */
  public void recordIfRejected(Payment payment, BusinessException e) {
    if (!isDeterministicRejection(e)) {
      return;
    }
    try {
      Refund failed =
          Refund.failed(
              payment.getId(),
              payment.getBookingId(),
              payment.getAmount(),
              ErrorStatus.PAYMENT_REFUND_FAILED.getMessage(),
              LocalDateTime.now());
      paymentCancelPersister.persistFailedRefund(failed);
    } catch (DataIntegrityViolationException dup) {
      // 재전달/DLT 재처리로 이미 FAILED 이력이 있으면 payment_id unique 위반 = 정상 멱등. 원 예외는 호출부에서 그대로 재던져 보상된다.
      log.info("FAILED 환불 이력이 이미 존재합니다(멱등). bookingId={}", payment.getBookingId());
    } catch (Exception ex) {
      // 이력 저장 실패는 추적 누락이다. 원 환불 실패 예외를 가리지 않도록 삼키고 error 로그로 알람 대상화한다(#297 recordFailedPayment와
      // 동일).
      log.error("FAILED 환불 이력 저장에 실패했습니다. bookingId={}", payment.getBookingId(), ex);
    }
  }
}

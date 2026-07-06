package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalRequest;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalResponse;
import com.ticketrush.boundedcontext.payment.out.repository.ExpiredBookingRepository;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 승인(confirm) UseCase.
 *
 * <p>쓰기가 {@code paymentRepository.saveAndFlush} 단건뿐이라 여러 문장을 묶는 트랜잭션이 필요 없다. 따라서 PG 승인(외부 왕복)을 트랜잭션
 * 안에 가두지 않아 DB 커넥션을 장시간 점유하지 않는다. 이벤트는 saveAndFlush(자체 트랜잭션 커밋) 성공 이후에 호출되므로, 커밋에 성공한 데이터만 외부로
 * 전파된다. (ID 전략이 IDENTITY라 {@code save}만으로도 INSERT가 즉시 실행되지만, 동시 confirm 시 unique 위반을 이벤트 발행 이전에
 * 표면화한다는 의도를 명시하고 취소 경로({@code PaymentCancelPersister})와 일관성을 맞추기 위해 saveAndFlush를 쓴다, #296.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmUseCase {

  /**
   * FAILED 이력으로 기록할 실패 사유 화이트리스트(#297).
   *
   * <p>과금이 발생하지 않은 것이 확실하고 결정적인 실패(카드 거절/한도 초과/세션 만료)만 기록한다. 승인 결과가 성공이거나(예: {@code
   * PAYMENT_ALREADY_COMPLETED} = PG 승인·과금 완료) 불명인 경우(예: {@code PAYMENT_PG_COMMUNICATION_FAILED} 통신
   * 실패, {@code PAYMENT_APPROVAL_FAILED} 미매핑, {@code PAYMENT_AMOUNT_MISMATCH} = 승인 후 검증 실패라 과금됨), 우리
   * 키/인증 결함({@code PAYMENT_PG_AUTH_FAILED})은 FAILED로 확정하면 실제 성공·과금 건을 "결제 안 됨"으로 오라벨링하는 고아 청구가 되거나
   * 사용자 결제실패 통계에 노이즈가 되므로 기록하지 않는다.
   */
  private static final Set<ErrorStatus> RECORDABLE_FAILURES =
      EnumSet.of(
          ErrorStatus.PAYMENT_METHOD_REJECTED,
          ErrorStatus.PAYMENT_LIMIT_EXCEEDED,
          ErrorStatus.PAYMENT_SESSION_NOT_FOUND);

  private final PaymentRepository paymentRepository;
  private final PaymentApprovalClientRouter paymentApprovalClientRouter;
  private final PaymentEventPublisher paymentEventPublisher;
  private final ExpiredBookingRepository expiredBookingRepository;
  private final PaymentMapper paymentMapper;
  private final MeterRegistry meterRegistry;

  public PaymentConfirmResponse execute(Long userId, PaymentConfirmRequest request) {
    if (paymentRepository.existsByBookingIdAndStatus(
        request.bookingId(), PaymentStatus.COMPLETED)) {
      throw new BusinessException(ErrorStatus.PAYMENT_ALREADY_COMPLETED);
    }

    // 만료 이벤트를 이미 수신한 booking이면 PG 호출 전 차단한다(best-effort 방어선, #224).
    if (expiredBookingRepository.existsByBookingId(request.bookingId())) {
      throw new BusinessException(ErrorStatus.BOOKING_EXPIRED);
    }

    String orderId = generateOrderId(request.bookingId());

    // PG 승인·금액 검증 단계에서 결제 실패가 확정되면(PG 거절/금액 불일치) 예외만 던지지 않고 FAILED 이력을 남긴다(#297).
    PaymentApprovalResponse approval;
    try {
      Timer.Sample sample = Timer.start(meterRegistry);
      try {
        approval =
            paymentApprovalClientRouter.approve(
                new PaymentApprovalRequest(
                    request.provider(),
                    request.paymentKey(),
                    orderId,
                    request.bookingId(),
                    request.amount()));
      } finally {
        sample.stop(
            Timer.builder(MetricNames.PAYMENT_PG_APPROVE)
                .tag(
                    MetricNames.TAG_PROVIDER,
                    request.provider() != null ? request.provider().name() : "unknown")
                .register(meterRegistry));
      }

      if (!request.amount().equals(approval.approvedAmount())) {
        throw new BusinessException(ErrorStatus.PAYMENT_AMOUNT_MISMATCH);
      }
    } catch (BusinessException e) {
      recordFailedPayment(userId, request, e);
      throw e;
    }

    Payment payment =
        Payment.builder()
            .bookingId(request.bookingId())
            .userId(userId)
            .seatId(request.seatId())
            .provider(request.provider())
            .amount(approval.approvedAmount())
            .status(PaymentStatus.COMPLETED)
            .paymentKey(request.paymentKey())
            .approvalNumber(approval.approvalNumber())
            .paidAt(approval.approvedAt())
            .build();

    // 동일 booking 동시 confirm 시 uk_payment_completed_booking unique 위반을 이벤트 발행 이전에 표면화하기 위해
    // saveAndFlush로 INSERT를 flush한다. 위반(DataIntegrityViolationException)은 PaymentFacade가 멱등
    // 처리한다(#296).
    Payment saved = paymentRepository.saveAndFlush(payment);

    paymentEventPublisher.publishConfirmed(
        saved.getId(),
        saved.getBookingId(),
        request.seatId(),
        userId,
        saved.getAmount(),
        saved.getPaidAt());

    Counter.builder(MetricNames.PAYMENT_CONFIRM)
        .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_SUCCESS)
        .register(meterRegistry)
        .increment();

    return paymentMapper.toConfirmResponse(saved);
  }

  /**
   * 이미 확정된 결제를 bookingId로 조회해 멱등 응답으로 반환한다.
   *
   * <p>동시 confirm 요청이 unique 제약에 막혔을 때, 먼저 확정된 COMPLETED 결제를 돌려주기 위해 {@link
   * com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade}의 멱등 fallback에서 호출한다. bookingId
   * 기준 조회는 paymentKey 충돌의 상위집합이라, 동일 paymentKey 재수신과 동일 booking·다른 paymentKey 충돌을 모두 흡수한다(#296).
   */
  public PaymentConfirmResponse getConfirmedResponseByBookingId(Long bookingId) {
    Payment payment =
        paymentRepository
            .findFirstByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));
    return paymentMapper.toConfirmResponse(payment);
  }

  /**
   * 결제 실패가 확정된 시점에 FAILED Payment 이력을 남긴다(#297).
   *
   * <p>과금이 발생하지 않은 결정적 거절({@link #RECORDABLE_FAILURES})만 기록한다. 승인 결과가 성공이거나 불명인 사유(통신 실패, 미매핑 승인
   * 실패, 승인 후 금액 불일치 등)는 실제 성공·과금 건을 FAILED로 오라벨링하는 고아 청구가 되므로 기록하지 않는다. 실패 이력 저장 자체가 실패하더라도 원래의 결제
   * 실패 예외를 가리지 않도록 예외를 삼키고 로그만 남긴다(호출부에서 원 예외를 재던진다).
   */
  private void recordFailedPayment(
      Long userId, PaymentConfirmRequest request, BusinessException e) {
    if (!RECORDABLE_FAILURES.contains(e.getErrorStatus())) {
      return;
    }
    try {
      Payment failed =
          Payment.failed(
              request.bookingId(),
              userId,
              request.seatId(),
              request.provider(),
              request.amount(),
              e.getErrorStatus().getCode(),
              e.getErrorStatus().getMessage());
      paymentRepository.saveAndFlush(failed);
      Counter.builder(MetricNames.PAYMENT_CONFIRM)
          .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_FAILURE)
          .tag(MetricNames.TAG_REASON, e.getErrorStatus().getCode())
          .register(meterRegistry)
          .increment();
    } catch (Exception ex) {
      // 추적이 목적인 기능이라 이력 저장 실패는 집계 누락이다. error 레벨로 올려 알람/메트릭 대상이 되게 한다.
      log.error(
          "FAILED 결제 이력 저장에 실패했습니다. bookingId={}, failureCode={}",
          request.bookingId(),
          e.getErrorStatus().getCode(),
          ex);
    }
  }

  /* PG 공통 orderId 규격(6~64자, 영문/숫자/_/-, 현재 Toss 기준)을 만족하기 위해 zero-padding 한다. */
  private String generateOrderId(Long bookingId) {
    return "BKG-%07d".formatted(bookingId);
  }
}

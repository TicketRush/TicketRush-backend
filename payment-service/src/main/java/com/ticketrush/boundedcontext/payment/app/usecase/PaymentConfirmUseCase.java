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
import com.ticketrush.boundedcontext.payment.out.apiclient.PgRejectionException;
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

  /**
   * booking당 보존하는 FAILED 이력 상한(#333).
   *
   * <p>FAILED row는 unique 제약에 걸리지 않아 동일 booking에 무제한 누적될 수 있다. 화이트리스트({@link
   * #RECORDABLE_FAILURES})가 무작위 스팸은 이미 거르지만, 인증 사용자의 정상 거절 반복은 여전히 쌓인다. 첫 {@value}건까지는 재시도 이력으로
   * 보존하고(#297) 초과분은 저장하지 않아 팽창을 유계로 만든다. 초과 억제는 {@link
   * MetricNames#PAYMENT_FAILED_RECORD_SUPPRESSED} 메트릭으로 관측한다.
   *
   * <p>{@code execute}에 트랜잭션이 없어 count→insert 사이 TOCTOU가 있으므로 이 상한은 정확한 하드 리밋이 아니라 <b>근사 상한(soft
   * cap)</b>이다. 동시 실패가 겹치면 상한을 근소하게 넘길 수 있으나, 목적이 "무제한 누적 방지(유계화)"라 수용한다(엄밀 상한은 DDL/락이 필요하나 이번 범위는
   * 스키마 무변경).
   */
  private static final long MAX_FAILED_HISTORY_PER_BOOKING = 5;

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
    // PG(Toss)가 원본 거절 코드/사유를 실어 보낸 경우(PgRejectionException) 내부 ErrorStatus와 함께 원본도 남긴다(#332).
    String pgFailureCode = null;
    String pgFailureReason = null;
    if (e instanceof PgRejectionException pge) {
      pgFailureCode = pge.getRawCode();
      pgFailureReason = pge.getRawMessage();
    }
    try {
      // booking당 FAILED 이력이 상한에 도달하면 더 쌓지 않는다(무제한 누적/쓰기 증폭 방어, #333). 첫 상한건까지는 이력으로
      // 보존되고, 초과 실패는 저장 대신 억제 메트릭으로만 관측한다. count 조회도 이 try 안에서 수행해, 조회가 DB 장애로 실패하더라도
      // 아래 catch가 삼켜 원래의 결제 실패 예외를 가리지 않게 한다(저장 실패와 동일한 불변식).
      if (paymentRepository.countByBookingIdAndStatus(request.bookingId(), PaymentStatus.FAILED)
          >= MAX_FAILED_HISTORY_PER_BOOKING) {
        Counter.builder(MetricNames.PAYMENT_FAILED_RECORD_SUPPRESSED)
            .tag(MetricNames.TAG_REASON, e.getErrorStatus().getCode())
            .register(meterRegistry)
            .increment();
        // 억제 자체는 위 메트릭으로 관측한다. 상한 도달 booking의 반복 재시도로 로그가 증폭되지 않도록 debug로 남긴다.
        log.debug(
            "FAILED 결제 이력이 booking당 상한({})에 도달해 기록을 억제합니다. bookingId={}, failureCode={}",
            MAX_FAILED_HISTORY_PER_BOOKING,
            request.bookingId(),
            e.getErrorStatus().getCode());
        return;
      }
      Payment failed =
          Payment.failed(
              request.bookingId(),
              userId,
              request.seatId(),
              request.provider(),
              request.amount(),
              e.getErrorStatus().getCode(),
              e.getErrorStatus().getMessage(),
              pgFailureCode,
              pgFailureReason);
      paymentRepository.saveAndFlush(failed);
      Counter.builder(MetricNames.PAYMENT_CONFIRM)
          .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_FAILURE)
          .tag(MetricNames.TAG_REASON, e.getErrorStatus().getCode())
          .register(meterRegistry)
          .increment();
    } catch (Exception ex) {
      // 추적이 목적인 기능이라 이력 기록 실패는 집계 누락이다. error 레벨로 올려 알람/메트릭 대상이 되게 한다.
      // 상한 조회(count)와 저장(saveAndFlush)이 같은 try 안이라, 어느 단계에서 실패했든 이 경로로 모인다(원 결제 실패 예외는 호출부가 재던진다).
      log.error(
          "FAILED 결제 이력 기록(상한 조회·저장)에 실패했습니다. bookingId={}, failureCode={}",
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

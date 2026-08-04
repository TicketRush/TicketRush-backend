package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.BookingRestClient;
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
 *
 * <h2>PG 호출 전 가드 계층 (#490)</h2>
 *
 * <p>PG 승인은 되돌릴 수 없는 부작용(과금)이므로 그 앞의 가드는 <b>싼 것부터 순서대로</b> 둔다. 셋 다 목적이 다르며 하나가 다른 하나를 대체하지 않는다.
 *
 * <ol>
 *   <li><b>COMPLETED 중복</b>(로컬 DB) — 이미 확정된 결제의 재요청을 막는다(#296).
 *   <li><b>{@code expired_booking} fast-path</b>(로컬 DB) — 만료 이벤트를 <b>이미 수신한</b> booking을 네트워크 왕복 없이
 *       거른다. 도착에 의존하므로 best-effort이며(#224), 이벤트가 늦으면 그냥 통과한다.
 *   <li><b>예매 상태 동기 확인</b>(booking-service 호출) — 이벤트 도착과 무관하게 <b>지금</b>의 상태를 보는 방어선이다(#490). 대량 만료
 *       구간에서 outbox 릴레이가 밀려 2번이 분 단위로 무력화된 것이 실측됐고(#345, 약 13분), 그 창에서 과금 후 좌석 미확정이 발생했다.
 * </ol>
 *
 * <p><b>3번도 창을 없애지는 못한다.</b> 상태를 읽은 뒤 PG 승인이 끝나기까지 사이에 만료가 일어나면 같은 결과가 나온다 — 분 단위였던 창이 왕복 한 번(수십
 * ms~1s)으로 줄어든 것이지 사라진 것이 아니다. 그 잔여 창에서 과금이 나면 되돌릴 자동 보상 경로는 아직 없다(#492). 이 가드를 완전 해결로 읽으면 안 된다.
 *
 * <p>2번을 남긴 이유는 3번이 네트워크 왕복이기 때문이다. 이미 만료가 확정된 건은 로컬 조회 하나로 끝내는 편이 싸고, booking-service가 죽어 있어도 그
 * 경로는 계속 동작한다.
 *
 * <p>3번의 판정은 <b>허용 목록</b>이다 — {@code PENDING}만 통과시키고 나머지는 전부 막는다. 차단 목록(예: EXPIRED만 거부)으로 짜면 #559가
 * 추가한 사용자 PENDING 즉시 취소 경로(CANCELED)가 그대로 샌다. 기본이 거부라 booking이 상태를 추가해도 payment는 컴파일 에러 없이 자동으로
 * 막는다.
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

  /**
   * 결제 확정을 허용하는 유일한 예매 상태 (#490).
   *
   * <p>booking-service {@code BookingStatus}의 이름이 문자열로 건너오므로 컴파일러가 지켜주지 못한다. 그래서 판정을 "이 값이 아니면 거부"로
   * 짜, 상대가 값을 바꾸거나 추가해도 통과가 아니라 차단으로 기울게 한다.
   */
  private static final String BOOKING_STATUS_PENDING = "PENDING";

  private static final String BOOKING_STATUS_EXPIRED = "EXPIRED";

  /**
   * 차단 메트릭의 {@code reason} 태그로 그대로 쓸 수 있는 상태 값 (#490).
   *
   * <p>태그 값은 카디널리티가 폭발하지 않도록 유한 집합이어야 하는데, 상태는 외부 서비스가 주는 문자열이라 그 보장이 코드에 없다. 알려진 값만 통과시키고 나머지는
   * {@code unknown}으로 접어 상한을 만든다.
   */
  private static final Set<String> KNOWN_BOOKING_STATUSES =
      Set.of(
          BOOKING_STATUS_PENDING,
          "CONFIRMED",
          "CANCELED",
          "REFUNDING",
          "REFUNDED",
          BOOKING_STATUS_EXPIRED);

  private static final String BOOKING_STATUS_UNKNOWN = "unknown";

  /** 상태 판정에 도달하지 못하고 조회 단계에서 막힌 건의 사유 태그. */
  private static final String REASON_LOOKUP_FAILED = "lookup_failed";

  private static final String REASON_BOOKING_NOT_FOUND = "not_found";

  private final PaymentRepository paymentRepository;
  private final PaymentApprovalClientRouter paymentApprovalClientRouter;
  private final PaymentEventPublisher paymentEventPublisher;
  private final ExpiredBookingRepository expiredBookingRepository;
  private final BookingRestClient bookingRestClient;
  private final PaymentMapper paymentMapper;
  private final MeterRegistry meterRegistry;

  public PaymentConfirmResponse execute(Long userId, PaymentConfirmRequest request) {
    if (paymentRepository.existsByBookingIdAndStatus(
        request.bookingId(), PaymentStatus.COMPLETED)) {
      throw new BusinessException(ErrorStatus.PAYMENT_ALREADY_COMPLETED);
    }

    // 만료 이벤트를 이미 수신한 booking이면 PG 호출 전 차단한다(best-effort 방어선, #224).
    // 로컬 조회라 아래 동기 확인보다 싸고, booking-service가 죽어 있어도 계속 동작한다.
    if (expiredBookingRepository.existsByBookingId(request.bookingId())) {
      throw new BusinessException(ErrorStatus.BOOKING_EXPIRED);
    }

    // 이벤트 도착과 무관하게 '지금'의 예매 상태를 확인한다(결정적 방어선, #490).
    // 위 fast-path는 이벤트가 늦으면 통과시키는데, 대량 만료 구간에서 그 창이 분 단위임이 실측됐다(#345).
    assertBookingIsPayable(request.bookingId());

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
   * PG 호출 전에 예매가 아직 결제 가능한 상태인지 booking-service에 동기 확인한다 (#490).
   *
   * <p><b>{@code PENDING}만 통과시키는 허용 목록이다.</b> 만료(EXPIRED)뿐 아니라 사용자 자가 취소(CANCELED, #559), 이미 확정된
   * 예매(CONFIRMED), 환불 진행/완료(REFUNDING·REFUNDED)가 모두 여기서 막힌다. 거절 사유 코드만 상태에 따라 가르는데, 기본값이 {@code
   * BOOKING_CONFIRM_NOT_ALLOWED}라 booking이 상태를 추가해도 payment는 자동으로 차단한다.
   *
   * <p>사유 코드는 booking 도메인의 {@code Booking.confirm()} 규칙과 같은 언어를 쓴다 — EXPIRED만 "만료된 예매"이고 나머지는 "확정할
   * 수 없는 예매 상태"다. 전부 {@code BOOKING_EXPIRED}로 뭉개면 사용자가 방금 직접 취소한 예매에도 "만료됐다"고 답하게 되어 사실이 아니고, CS
   * 문의가 오진단된다.
   *
   * <p>조회 자체가 실패하면 {@link com.ticketrush.boundedcontext.payment.out.apiclient.BookingRestClient}가
   * 예외로 끊으므로 이 메서드는 통과시키지 않는다 (fail-closed 근거는 그 클래스 javadoc 참고). 그 예외는 PG 호출 try 블록 <b>밖</b>에서 나므로
   * {@link #recordFailedPayment} 경로를 타지 않는다 — 과금이 일어나지 않은 차단이라 FAILED 이력을 남길 이유가 없고, 화이트리스트에도 없다.
   */
  private void assertBookingIsPayable(Long bookingId) {
    String bookingStatus;
    try {
      bookingStatus = bookingRestClient.getBooking(bookingId).bookingStatus();
    } catch (BusinessException e) {
      // 조회 실패로 막힌 건도 이 가드가 차단한 건이다. 여기서 세지 않으면 booking이 느려지거나 죽은 구간에서
      // 결제가 전건 거부되는 동안 차단 카운터가 0으로 평평해, 서킷이 없는 지금 관측 축이 통째로 빈다.
      incrementGuardBlocked(
          ErrorStatus.BOOKING_NOT_FOUND == e.getErrorStatus()
              ? REASON_BOOKING_NOT_FOUND
              : REASON_LOOKUP_FAILED);
      throw e;
    }

    if (BOOKING_STATUS_PENDING.equals(bookingStatus)) {
      return;
    }

    ErrorStatus errorStatus =
        BOOKING_STATUS_EXPIRED.equals(bookingStatus)
            ? ErrorStatus.BOOKING_EXPIRED
            : ErrorStatus.BOOKING_CONFIRM_NOT_ALLOWED;

    incrementGuardBlocked(metricReasonOf(bookingStatus));
    log.warn(
        "결제 가능한 예매 상태가 아니라 PG 승인 전에 차단합니다. bookingId={}, bookingStatus={}",
        bookingId,
        bookingStatus);

    throw new BusinessException(errorStatus);
  }

  private void incrementGuardBlocked(String reason) {
    Counter.builder(MetricNames.PAYMENT_CONFIRM_BOOKING_GUARD_BLOCKED)
        .tag(MetricNames.TAG_REASON, reason)
        .register(meterRegistry)
        .increment();
  }

  /**
   * 알려진 상태만 태그로 내보내 메트릭 카디널리티를 유계로 만든다.
   *
   * <p>{@code null}을 먼저 거른다 — {@code Set.of(...)}가 만드는 불변 Set은 {@code contains(null)}에 NPE를 던진다. 응답
   * DTO가 {@code ignoreUnknown = true}라 booking이 필드명을 바꾸거나 URL이 다른 200 응답을 주는 엔드포인트로 오설정되면 상태가 조용히
   * null이 되는데, 그때 차단은 되더라도 사용자에게 409/503이 아닌 원시 500이 나가면 {@code BookingRestClient}가 약속한 "판정 불가는 모두
   * 503으로 수렴"이 이 지점에서 깨진다.
   */
  private String metricReasonOf(String bookingStatus) {
    return bookingStatus != null && KNOWN_BOOKING_STATUSES.contains(bookingStatus)
        ? bookingStatus
        : BOOKING_STATUS_UNKNOWN;
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

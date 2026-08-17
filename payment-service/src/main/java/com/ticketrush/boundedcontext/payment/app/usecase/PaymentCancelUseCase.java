package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentCancelRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentCancelResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentCancelPersister.CancelPersisted;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelCommand;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelResult;
import com.ticketrush.boundedcontext.payment.out.apiclient.TicketRestClient;
import com.ticketrush.boundedcontext.payment.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.boundedcontext.payment.out.repository.RefundRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 취소(환불) UseCase.
 *
 * <p>본인 결제 검증 → 상태 검증 → 입장권 사용 여부 조회 → 예매번호 조회 → PG 취소 호출 → {@link Refund} 영속화 → {@code
 * PaymentStatus.CANCELED} 전이 → {@code PaymentCanceledEvent} 발행까지의 happy path를 담당한다. 동일 결제에 대한 중복
 * 요청은 기존 환불 내역을 반환하여 멱등하게 처리한다.
 *
 * <p>PG 취소(외부 왕복)는 트랜잭션 밖에서 호출해 DB 커넥션 장시간 점유를 피하고, 영속화(refund 저장 + 상태 전이)만 {@link
 * PaymentCancelPersister}의 짧은 트랜잭션으로 분리한다. 이벤트는 영속화 커밋 이후(트랜잭션 밖)에 발행하므로, 커밋에 성공한 데이터만 외부로 전파된다.
 *
 * <p><b>외부 조회 2회는 모두 PG 취소 앞에 둔다(#416, #608).</b> 둘 다 "판정하지 못하면 취소를 거부한다"는 fail-closed이고, 그 자리가 PG
 * 앞이어야만 거부가 실제로 가능하다. PG 취소가 나간 뒤에 막으면 <b>환불은 됐는데 좌석이 SOLD로 남는</b> 상태가 되는데, 관리자 강제 해제가 SOLD를 거부하므로
 * 그 좌석은 수동 DML 외에 풀 수 없다. 반면 PG 앞에서 거부하면 과금·예매·좌석이 전부 그대로 남아 사용자가 재시도하는 것만으로 회복된다 — <b>되돌릴 수 있는 실패를
 * 고른다</b>는 것이 이 순서의 이유다(ADR 0011 원칙 3과 같은 판단).
 *
 * <p>환불 기한 검증(공연 시작 시간 기준)과 환불 실패 시 보상 트랜잭션(#91)은 본 UseCase 범위 밖이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelUseCase {

  /** booking 이 의도적으로 낸 404 — 결제는 있는데 예매가 없다. */
  private static final String BLOCKED_NOT_FOUND = "not_found";

  /** 통신 실패·타임아웃·계약 붕괴로 조회 자체에 실패 — booking 장애 신호다. */
  private static final String BLOCKED_LOOKUP_FAILED = "lookup_failed";

  /** 200 을 받았는데 예매번호가 비어 있음 — booking 이 필드를 내려주지 않는다는 뜻이라 배포 사고다. */
  private static final String BLOCKED_BOOKING_NUMBER_UNKNOWN = "booking_number_unknown";

  private final PaymentRepository paymentRepository;
  private final RefundRepository refundRepository;
  private final PaymentCancelClientRouter paymentCancelClientRouter;
  private final PaymentCancelPersister paymentCancelPersister;
  private final FailedRefundRecorder failedRefundRecorder;
  private final PaymentEventPublisher paymentEventPublisher;
  private final TicketRestClient ticketRestClient;
  private final BookingRestClient bookingRestClient;
  private final PaymentMapper paymentMapper;
  private final MeterRegistry meterRegistry;

  public PaymentCancelResponse execute(Long userId, Long paymentId, PaymentCancelRequest request) {
    Payment payment =
        paymentRepository
            .findByIdAndUserId(paymentId, userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));

    // 이미 취소된 결제는 기존 환불 내역을 그대로 반환하여 멱등 처리한다.
    if (payment.getStatus() == PaymentStatus.CANCELED) {
      return toExistingRefundResponse(payment, paymentId);
    }

    if (payment.getStatus() != PaymentStatus.COMPLETED) {
      throw new BusinessException(ErrorStatus.PAYMENT_NOT_CANCELABLE);
    }

    // 이미 입장(ticket=USED)한 예매는 환불을 막는다 (#416). 이 검증이 없으면 결제 취소가 booking을 우회해 착석한 좌석이
    // 환불 이벤트로 AVAILABLE 반환되어 재판매된다(#399가 세운 정책의 우회). PG 왕복과 마찬가지로 트랜잭션 밖에서 조회한다.
    if (ticketRestClient.isTicketUsed(payment.getBookingId())) {
      throw new BusinessException(ErrorStatus.PAYMENT_CANCEL_NOT_ALLOWED_TICKET_USED);
    }

    // 좌석 소유 교차검증에 쓸 예매번호를 확보한다 (#608). payment 는 예매번호를 자신의 테이블에 갖고 있지
    // 않아 이 조회로만 얻는다. 얻지 못하면 아래 PG 취소로 넘어가지 않고 여기서 끊는다.
    String bookingNumber = lookupBookingNumber(payment.getBookingId());

    // PG 취소는 트랜잭션 밖에서 호출한다(외부 왕복 동안 DB 커넥션을 점유하지 않기 위함).
    PaymentCancelResult result;
    try {
      result =
          paymentCancelClientRouter.cancel(
              new PaymentCancelCommand(
                  payment.getProvider(),
                  payment.getPaymentKey(),
                  payment.getAmount(),
                  request.reason(),
                  generateIdempotencyKey(paymentId)));
    } catch (BusinessException e) {
      // PG 거절이면 FAILED 환불 이력을 남긴다(부수효과). 원 예외는 그대로 던져 사용자에게 실패를 전달한다(#334).
      failedRefundRecorder.recordIfRejected(payment, e);
      throw e;
    }

    Refund refund =
        Refund.completed(
            payment.getId(),
            payment.getBookingId(),
            payment.getAmount(),
            result.pgRefundKey(),
            request.reason(),
            LocalDateTime.now(),
            result.canceledAt());

    // 영속화(refund 저장 + 상태 전이)만 짧은 트랜잭션으로 분리한다. 동시 취소 시 unique 위반은 PaymentFacade가 멱등 처리한다.
    CancelPersisted persisted = paymentCancelPersister.persist(paymentId, refund);

    // 발행은 영속화 커밋 이후(트랜잭션 밖)에 호출한다 → 커밋에 성공한 데이터만 전파된다.
    Payment canceled = persisted.payment();
    Refund saved = persisted.refund();
    paymentEventPublisher.publishCanceled(
        canceled.getId(),
        canceled.getBookingId(),
        bookingNumber,
        canceled.getSeatId(),
        saved.getId(),
        saved.getPrice(),
        saved.getReason(),
        saved.getConfirmedAt());

    return paymentMapper.toCancelResponse(canceled, saved);
  }

  /**
   * 동시 취소 요청이 unique 제약에 막혀 별도 트랜잭션에서 먼저 환불이 확정된 경우, 그 환불 내역을 멱등하게 반환한다. PaymentFacade가 {@code
   * DataIntegrityViolationException}을 잡은 뒤 호출한다.
   */
  @Transactional(readOnly = true)
  public PaymentCancelResponse getCanceledResponse(Long userId, Long paymentId) {
    Payment payment =
        paymentRepository
            .findByIdAndUserId(paymentId, userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));
    return toExistingRefundResponse(payment, paymentId);
  }

  private PaymentCancelResponse toExistingRefundResponse(Payment payment, Long paymentId) {
    Refund existing =
        refundRepository
            .findByPaymentId(paymentId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_REFUND_INCONSISTENT));
    // FAILED 이력만 있고 취소 완료가 아니면 "취소됨"으로 응답하면 안 된다. unique 슬롯을 FAILED가 점유한 상태의 충돌(#334)을
    // 멱등 성공으로 오분류해 미환불/미정합을 은폐하는 것을 막고, 불일치로 표면화한다.
    if (existing.getStatus() != RefundStatus.COMPLETED) {
      throw new BusinessException(ErrorStatus.PAYMENT_REFUND_INCONSISTENT);
    }
    return paymentMapper.toCancelResponse(payment, existing);
  }

  /**
   * 좌석 소유 교차검증(ABA 방지)에 쓸 예매번호를 booking 에서 조회한다 (#608).
   *
   * <p><b>얻지 못하면 취소를 진행시키지 않는다.</b> 값 없이 발행하면 {@code PaymentCanceledEvent} 의 {@code bookingNumber}
   * 가 비고, seat 의 좌석 소유 교차검증이 통째로 꺼져 그 사이 다른 사용자에게 팔린 SOLD 좌석을 AVAILABLE 로 되돌린다. 이 호출이 PG 취소
   * <b>앞</b>에 있어야 그 거부가 실제로 가능하다 — 뒤에 두면 환불만 나가고 좌석은 SOLD 로 남는데, 관리자 강제 해제도 SOLD 를 거부하므로 수동 DML 외에
   * 푸는 수단이 없다.
   *
   * <p>차단 사유를 태그로 가르는 이유는 사용자 응답이 전부 "취소 실패"로 동일화되기 때문이다. {@code lookup_failed} 는 booking 장애지만
   * {@code booking_number_unknown} 은 booking 이 필드를 내려주지 않는다는 뜻이라 배포 사고이고, 그 구간에는 취소가 전건 막힌다.
   */
  private String lookupBookingNumber(Long bookingId) {
    BookingInfoResponse booking;
    try {
      booking = bookingRestClient.getBooking(bookingId);
    } catch (BusinessException e) {
      boolean notFound = e.getErrorStatus() == ErrorStatus.BOOKING_NOT_FOUND;
      countBlocked(notFound ? BLOCKED_NOT_FOUND : BLOCKED_LOOKUP_FAILED);
      log.warn(
          "결제 취소 차단: 예매 조회에 실패했습니다. bookingId={}, errorStatus={}", bookingId, e.getErrorStatus());
      throw e;
    } catch (Exception e) {
      // 클라이언트가 접지 못한 예외까지 여기서 센다. 새어 나가면 사용자에게 원시 500 이 나가는 것보다
      // 이 카운터가 침묵하는 것이 더 나쁘다 — 유일한 관측 축이라 그 구간의 차단이 통째로 안 보인다
      // (ADR 0015 판정 3 과 같은 규율).
      countBlocked(BLOCKED_LOOKUP_FAILED);
      log.error("결제 취소 차단: 예매 조회에서 예기치 못한 오류가 발생했습니다. bookingId={}", bookingId, e);
      throw new BusinessException(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED, e);
    }

    String bookingNumber = booking.bookingNumber();
    if (bookingNumber == null || bookingNumber.isBlank()) {
      // 배포 순서가 역전돼 booking 이 아직 이 필드를 내려주지 않을 때도 같은 경로로 비어 오므로, 이 가드가
      // 그 구간의 안전장치를 겸한다. 도메인 오류가 아니라 계약 결함이라 404 가 아닌 503 으로 접는다(ADR 0011 원칙 3).
      countBlocked(BLOCKED_BOOKING_NUMBER_UNKNOWN);
      log.error(
          "[CRITICAL] 예매번호를 얻지 못해 결제 취소를 차단했습니다! booking 응답 계약을 확인해야 합니다. bookingId={}", bookingId);
      throw new BusinessException(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);
    }
    return bookingNumber;
  }

  private void countBlocked(String reason) {
    Counter.builder(MetricNames.PAYMENT_CANCEL_BOOKING_GUARD_BLOCKED)
        .tag(MetricNames.TAG_REASON, reason)
        .register(meterRegistry)
        .increment();
  }

  /* 동일 결제의 재요청 시 PG 측 중복 취소를 막기 위해 paymentId 기반 고정 멱등 키를 생성한다. */
  private String generateIdempotencyKey(Long paymentId) {
    return "REFUND-%07d".formatted(paymentId);
  }
}

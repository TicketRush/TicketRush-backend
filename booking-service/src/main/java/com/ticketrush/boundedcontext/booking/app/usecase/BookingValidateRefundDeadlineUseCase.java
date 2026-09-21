package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.policy.RefundDeadlinePolicy;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.apiclient.PerformanceRestClient;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 공연 7일 전(D-7)을 지난 예매의 <b>환불 개시</b>를 차단한다 (#668).
 *
 * <p>이 제한은 원래 프론트 {@code isRefundableBooking}에만 있어 {@code DELETE /api/v1/booking/{bookingNumber}}를
 * 직접 호출하면 공연 당일에도 환불이 성사되고 PG 환불까지 진행됐다. 인가 우회가 아니라 비즈니스 규칙 우회지만 금전이 오가는 경로이므로 신뢰 경계인 서버에서 검증한다. 판정
 * 기준(7일·공연 시작 시각·경계 포함)은 {@link RefundDeadlinePolicy}가 소유한다.
 *
 * <p><b>사용자의 CONFIRMED 환불 경로에만 건다.</b> 기존 입장 가드({@code BookingValidateTicketNotUsedUseCase})와 클래스를
 * 나눈 이유가 이것이다 — 그쪽 {@code executeForAdmin}은 관리자 환불과 재환불이 함께 쓰는데, 여기서는 그 두 경로 모두 D-7을 적용하면 안 된다.
 *
 * <ul>
 *   <li>PENDING 즉시취소 — 결제 전 이탈이라 환불이 아니다. 언제든 허용돼야 한다.
 *   <li>관리자 환불({@code POST /{n}/refund}) — 정책 예외를 처리하려고 있는 CS 창구다. 막으면 대응 수단이 사라진다.
 *   <li>관리자 재환불({@code POST /{n}/refund-retry}) — REFUNDING 고착(#397) 복구를 겸한다. 막으면 고착을 빠져나올 유일한 수단이
 *       사라져 ADR 5가 제거한 흡수 상태가 되살아난다. 고착은 공연이 지난 뒤 발견되는 경우가 주 대상이라, D-7을 걸면 정확히 그 건들이 복구 불가가 된다.
 * </ul>
 *
 * <p><b>검사 대상은 환불이 성사될 수 있는 예매뿐이다.</b> {@code CONFIRMED}와 {@code REFUNDING}을 검사하고 그 밖은 조회 없이 통과시킨다
 * — 근거는 {@link #isRefundable(Booking)} 참고. PENDING은 위 이유로 대상이 아니고, 나머지 상태는 CONFIRMED로 돌아오는 경로가 없어
 * 환불이 성사될 수 없다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> performance-service 왕복이 {@code @Transactional} 안에 들어가면 다운스트림 지연이 booking의
 * DB 커넥션 풀을 물어 예매 경로 전체를 마비시킨다({@code BookingValidateTicketNotUsedUseCase}와 같은 규율).
 *
 * <p><b>조회에 실패하면 막는다(fail-closed).</b> {@code PerformanceRestClient}는 예매 목록 조회를 위해 의도적으로
 * fail-open이므로(#560 부분 응답 정책) 빈 결과를 "제한 없음"으로 해석하면 performance-service 장애가 곧 D-7 무력화가 된다. 공연 일시를 알
 * 수 없는 경우는 모두 {@code BOOKING_PERFORMANCE_COMMUNICATION_FAILED}(503)로 수렴시킨다 — {@code
 * TicketRestClient}가 "알 수 없으면 막는다"를 적용하는 방식과 같다.
 *
 * <p>이 검증과 {@code requestRefund()} 커밋 사이의 좁은 창(수 ms)은 입장 가드와 마찬가지로 남는다. 분산 락은 도입하지 않았다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingValidateRefundDeadlineUseCase {

  private final BookingRepository bookingRepository;
  private final PerformanceRestClient performanceRestClient;
  private final RefundDeadlinePolicy refundDeadlinePolicy;

  /**
   * 소유자의 예매 취소 경로. 소유권을 먼저 검증해, 비소유자가 응답으로 타인 예매의 공연 일정을 알아내지 못하게 한다(뒤따르는 {@code
   * BookingCancelMyBookingUseCase}와 동일한 조회 조건).
   */
  public void execute(Long userId, String bookingNumber) {
    Booking booking =
        bookingRepository
            .findByBookingNumberAndUserId(bookingNumber, userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    if (!isRefundable(booking)) {
      return;
    }

    if (!allowsRefund(booking)) {
      log.info("[BookingValidateRefundDeadline] 환불 마감이 지나 거절합니다. bookingNumber={}", bookingNumber);
      throw new BusinessException(ErrorStatus.BOOKING_REFUND_DEADLINE_PASSED);
    }
  }

  /**
   * internal 조회용 판정(#668). 예외가 아니라 <b>값</b>으로 답한다 — 호출자는 payment 의 결제 취소 경로고, 그쪽은 자신의 에러 코드로 거절해야
   * 하기 때문이다.
   *
   * <p><b>판정 주체를 booking 에 둔다.</b> payment 가 공연을 직접 조회해 계산하게 하면 {@code RefundDeadlinePolicy} 의 7일이
   * 세 번째로 하드코딩되고, 세 곳이 가직가직 움직이면 정책이 조용히 갈라진다.
   *
   * <p>마감 정책의 대상이 아닌 상태는 {@code true}를 돌려준다 — "마감은 이 요청을 막지 않는다"는 뜻이지 "환불해도 좋다"는 뜻이 아니다. 상태 자체의 판정은
   * 호출자의 다른 가드가 한다.
   *
   * @throws BusinessException {@code BOOKING_PERFORMANCE_COMMUNICATION_FAILED}(503) — 공연 일시를 확정할 수
   *     없을 때. 호출자가 이를 차단으로 읽어야 fail-closed 가 유지된다.
   */
  public boolean isRefundAllowed(Long bookingId) {
    Booking booking =
        bookingRepository
            .findById(bookingId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    return !isRefundable(booking) || allowsRefund(booking);
  }

  /** 공연을 조회해 마감 전인지 판정한다. 조회 실패는 던져서 막는다(fail-closed). */
  private boolean allowsRefund(Booking booking) {
    PerformanceInfoResponse performance = fetchPerformance(booking.getPerformanceId());
    return refundDeadlinePolicy.allowsRefund(performance.showDate(), performance.showTime());
  }

  /**
   * 환불이 성사될 수 있는 상태인가. {@code BookingValidateTicketNotUsedUseCase}와 같은 기준이다.
   *
   * <p>{@code REFUNDING}을 함께 보는 이유는 가드 읽기와 취소 트랜잭션 읽기 사이의 창 때문이다. {@code
   * recordRefundFailure}(#391)가 {@code REFUNDING}을 {@code CONFIRMED}로 되돌리므로, 여기서 {@code REFUNDING}을
   * 그냥 통과시키면 그 창에서 <b>마감 판정을 한 번도 받지 않은 예매가 환불을 개시</b>한다. {@code CONFIRMED}로 복원될 수 있는 상태가 {@code
   * REFUNDING}뿐이라 이 둘만 보면 창이 닫힌다.
   */
  private boolean isRefundable(Booking booking) {
    return booking.getBookingStatus() == BookingStatus.CONFIRMED
        || booking.getBookingStatus() == BookingStatus.REFUNDING;
  }

  /**
   * 공연 일시를 확정할 수 없으면 던진다.
   *
   * <p>{@code showDate}·{@code showTime}은 {@code Performance}에서 {@code nullable = false}라 정상 응답이면
   * 비어 있을 수 없다. 비어 있다면 스키마 변경이나 라우팅 오설정이므로 "제한 없음"으로 낙관하지 않는다.
   */
  private PerformanceInfoResponse fetchPerformance(Long performanceId) {
    PerformanceInfoResponse performance =
        performanceRestClient
            .getPerformance(performanceId)
            .orElseThrow(
                () -> {
                  log.warn(
                      "[BookingValidateRefundDeadline] 공연 조회에 실패해 환불을 차단합니다. performanceId={}",
                      performanceId);
                  return new BusinessException(
                      ErrorStatus.BOOKING_PERFORMANCE_COMMUNICATION_FAILED);
                });

    if (performance.showDate() == null || performance.showTime() == null) {
      log.warn(
          "[BookingValidateRefundDeadline] 공연 일시가 비어 환불을 차단합니다."
              + " performanceId={}, showDate={}, showTime={}",
          performanceId,
          performance.showDate(),
          performance.showTime());
      throw new BusinessException(ErrorStatus.BOOKING_PERFORMANCE_COMMUNICATION_FAILED);
    }

    return performance;
  }
}

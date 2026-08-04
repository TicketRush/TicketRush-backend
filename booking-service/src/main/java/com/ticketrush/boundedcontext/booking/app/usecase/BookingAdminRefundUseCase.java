package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.RefundRequestedEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 예매를 직접 환불 처리한다 (#561). 관리자 예매 관리 화면의 [환불 처리] 버튼이 호출하는 경로다.
 *
 * <p><b>{@link BookingAdminRetryRefundUseCase}와 대상이 정반대라 합치지 않았다.</b> 그쪽은 환불 실패 이력이 있는 건과 REFUNDING
 * 고착만 받는 <b>복구 도구</b>이고(그 외는 {@code BOOKING_409_005}로 거절한다), 이쪽은 아무 문제 없는 정상 CONFIRMED 예매를 관리자가 환불해
 * 주는 <b>관리 도구</b>다. 플래그 하나로 한 클래스에 합치면 이름이 하는 일과 어긋나는데, ADR 0005가 바로 그 불일치(REFUND_FAILED)를 문제의 근원으로
 * 지목했다. 겹치는 것은 감사 로그와 이벤트 발행 몇 줄뿐이다.
 *
 * <p><b>새 에러 코드를 만들지 않는다.</b> 대상 제한은 {@code requestRefund()}가 이미 한다 — CONFIRMED가 아니면 {@code
 * BOOKING_409_001}("취소할 수 없는 예매 상태")로 거절하며, 이는 사용자 취소 경로와 같은 계약이다.
 *
 * <p><b>PG 환불을 직접 호출하지 않는다.</b> booking은 {@code RefundRequestedEvent}만 발행하고, payment의 {@code
 * PaymentRefundByBookingUseCase}가 PG 취소·멱등·self-heal을 전부 담당한다. 좌석 반환과 REFUNDED 종결은 환불 성공 이벤트가 도착한
 * 뒤다(refund-first, ADR 0005) — 그래서 이 API의 응답 시점 상태는 REFUNDING이다.
 *
 * <p>입장 완료(입장권 USED) 예매의 차단은 {@code BookingValidateTicketNotUsedUseCase.executeForAdmin}이 트랜잭션 밖에서
 * 먼저 수행한다({@code BookingFacade} 참고).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BookingAdminRefundUseCase {

  private final BookingRepository bookingRepository;
  private final EventPublisher eventPublisher;
  private final Clock clock;

  public void execute(Long adminId, String bookingNumber) {
    Booking booking =
        bookingRepository
            .findByBookingNumber(bookingNumber)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    booking.requestRefund();

    // 실제 PG 환불을 유발하는 관리자 행위다. 누가 누구의 예매를 건드렸는지 추적 가능해야 한다.
    // 커밋 후에 남긴다 — 낙관적 락 충돌(사용자 취소와 경합)로 롤백되면 일어나지 않은 환불이 감사 기록에
    // 남아, 사고 조사 때 실제 이력과 구분되지 않는다.
    AdminAuditLogger.logAfterCommit(
        "관리자 환불 처리", adminId, booking.getBookingNumber(), booking.getId(), booking.getUserId());

    eventPublisher.publish(
        new RefundRequestedEvent(
            booking.getId(),
            booking.getBookingNumber(),
            booking.getSeatId(),
            booking.getUserId(),
            LocalDateTime.now(clock)));
  }
}

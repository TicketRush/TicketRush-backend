package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.SeatConfirmFailedEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 SOLD 확정 실패를 보상 신호로 발행한다 (#489).
 *
 * <p>결제는 승인됐고(과금 완료) 예매도 확정됐는데 좌석이 그 예매의 것이 아닌 상태다. 이 신호를 payment-service가 소비해 자동 환불로 되돌리는 것은 #492
 * 범위다.
 *
 * <p><b>예매 상태는 바꾸지 않는다.</b> CONFIRMED를 유지하고 종결은 환불 성공({@code PaymentCanceledEvent})에 매단다 —
 * refund-first 원칙(ADR 0005) 그대로다. 사용자 취소({@code RefundRequestedEvent}, CONFIRMED→REFUNDING)와 사고 보상을
 * 같은 신호로 뭉개지 않으려는 이유이기도 하다.
 *
 * <p><b>같은 신호의 중복 발행은 막지 않는다.</b> 결제 완료 이벤트가 재수신되면 좌석 확정이 다시 시도되고 같은 실패가 또 발행될 수 있다. 릴레이 자체가
 * at-least-once라 소비자는 어차피 {@code bookingId} 기준 멱등이어야 한다. 다만 그 멱등성은 <b>같은 신호의 중복</b>만 막고 <b>애초에 보내면
 * 안 되는 신호</b>는 막지 못하므로, CONFIRMED가 아닌 예매는 아래에서 걸러낸다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BookingPublishSeatConfirmFailedUseCase {

  private final BookingRepository bookingRepository;
  private final EventPublisher eventPublisher;

  public void execute(Long bookingId) {
    Booking booking =
        bookingRepository
            .findById(bookingId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    // CONFIRMED 가 아니면 보상 대상이 아니다. 이미 환불(REFUNDED)·환불 진행 중(REFUNDING)·취소된 예매의
    // PaymentConfirmedEvent 가 재전달되면(리밸런스나 DLT 리플레이) 좌석은 이미 반환돼 있어 409 가 뜬다.
    // 그때 신호를 내보내면 #492 가 이미 환불된 건에 두 번째 환불을 건다. 소비자의 bookingId 멱등은 같은
    // 신호의 중복만 막지, 애초에 보내면 안 되는 신호는 막지 못한다.
    if (booking.getBookingStatus() != BookingStatus.CONFIRMED) {
      log.warn(
          "좌석 확정 실패 신호를 발행하지 않는다(보상 대상 아님). bookingId: {}, bookingStatus: {}",
          bookingId,
          booking.getBookingStatus());
      return;
    }

    // 활성 트랜잭션 안에서 직접 발행한다 — OutboxEventPublisher가 트랜잭션을 강제하고(없으면 IllegalStateException),
    // afterCommit 발행은 커밋 후 유실 창이 생긴다.
    eventPublisher.publish(
        new SeatConfirmFailedEvent(
            booking.getId(),
            booking.getBookingNumber(),
            booking.getSeatId(),
            booking.getUserId(),
            LocalDateTime.now()));
  }
}

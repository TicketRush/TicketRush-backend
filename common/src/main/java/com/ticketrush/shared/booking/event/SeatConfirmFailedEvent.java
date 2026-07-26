package com.ticketrush.shared.booking.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;
import java.time.LocalDateTime;

/**
 * 결제·예매는 확정됐으나 좌석 SOLD 확정이 실패했을 때 booking-service가 발행하는 이벤트 (#489).
 *
 * <p>과금이 끝난 뒤 좌석이 그 예매의 것이 아님(만료 해제 또는 타 예매 점유)을 seat-service가 {@code SEAT_409_003}으로 알려온 경우다.
 * payment-service가 이를 소비해 보상(환불)을 거는 것은 #492 범위이며, 이번 변경은 발행까지다.
 *
 * <p><b>패키지가 {@code shared.booking}인 것은 기능 요구다.</b> {@code OutboxEventPublisher}가 이벤트 패키지의 {@code
 * .shared.} 다음 세그먼트로 aggregateType을 유도한다. 이름은 seat를 가리키지만 발행 주체는 booking이고, booking-service의 {@code
 * app.outbox.aggregate-types: [Booking]}과 맞아야 이 서비스의 릴레이가 집는다. {@code shared.seat}에 두면
 * aggregateType이 {@code Seat}가 되어, outbox 테이블을 공유하는 seat-service 릴레이가 booking이 쓴 행을 대신 발행하게 된다.
 *
 * <p><b>소비자는 {@code bookingId} 기준으로 멱등해야 한다.</b> 릴레이는 at-least-once이고(#344 실측 3.09배 증폭), 결제 완료 이벤트가
 * 재수신되면 좌석 확정도 다시 시도돼 같은 실패가 또 발행될 수 있다. 발행 측에 중복 억제 장치는 두지 않았다 — 소비자가 어차피 져야 할 책임이라 양쪽에서 막을 이유가
 * 없다(#489).
 */
public record SeatConfirmFailedEvent(
    Long bookingId, String bookingNumber, Long seatId, Long userId, LocalDateTime failedAt)
    implements DomainEvent {

  public static final String TOPIC = "seat-confirm-failed-topic";
  public static final String EVENT_NAME = "SeatConfirmFailed";

  @Override
  public String topic() {
    return TOPIC;
  }

  @Override
  public String key() {
    return String.valueOf(bookingId);
  }

  @Override
  public String aggregateId() {
    return String.valueOf(bookingId);
  }

  @Override
  public String eventName() {
    return EVENT_NAME;
  }

  @Override
  public String traceId() {
    return EventUtils.extractTraceId();
  }
}

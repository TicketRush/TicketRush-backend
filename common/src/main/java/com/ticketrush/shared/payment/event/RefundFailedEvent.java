package com.ticketrush.shared.payment.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;
import java.time.LocalDateTime;

/**
 * PG 환불이 최종(영구) 실패했을 때 payment-service가 발행하는 보상 이벤트 (#91).
 *
 * <p>booking-service가 수신해 예매를 {@code REFUNDING → CONFIRMED}로 복원하고 {@code failedAt}을 실패 시각으로 기록한 뒤
 * 관리자 알림을 남긴다(#391). 환불 실패는 취소가 성사되지 않았다는 뜻이므로 예매는 유효한 상태로 되돌아간다. 일시적(인프라) 실패는 재시도→DLT로 처리하고 본 이벤트를
 * 발행하지 않는다.
 */
public record RefundFailedEvent(
    Long bookingId, String bookingNumber, String reason, LocalDateTime failedAt)
    implements DomainEvent {

  public static final String TOPIC = "refund-failed-topic";
  public static final String EVENT_NAME = "RefundFailed";

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

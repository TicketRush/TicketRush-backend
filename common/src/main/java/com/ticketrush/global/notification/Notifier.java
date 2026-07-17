package com.ticketrush.global.notification;

import java.util.Map;

/**
 * 운영 알림 발송 추상화.
 *
 * <p>DLT 소비({@code DeadLetterConsumer})와 Outbox DEAD 전환({@code OutboxStatusUpdater}) 등 운영자 인지가 필요한
 * 실패 지점에서 사용한다. 알림 채널(Slack 등)의 구현 세부는 구현체가 감춘다.
 *
 * <p>구현체는 알림 전송 실패가 호출자 흐름을 깨지 않도록 예외를 던지지 않고 내부에서 처리(로깅)해야 한다.
 */
public interface Notifier {

  /**
   * 알림을 발송한다.
   *
   * @param title 알림 제목(핵심 요약)
   * @param message 본문 상세(예: 예외 메시지)
   * @param metadata 부가 컨텍스트(토픽/offset/eventId 등). {@code null}이면 부가 정보 없이 발송한다.
   */
  void send(String title, String message, Map<String, String> metadata);
}

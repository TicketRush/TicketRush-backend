package com.ticketrush.global.event;

/**
 * Kafka 컨슈머 groupId의 단일 정의처(SSOT) (#269).
 *
 * <p>서비스별 groupId 리터럴이 리스너마다 흩어져 오타·불일치를 유발하던 것을 이 클래스로 통일한다.
 *
 * <p>{@code @KafkaListener(groupId = ...)}는 컴파일 타임 상수 String을 요구하므로 반드시 {@code public static final
 * String} 리터럴로 둔다(enum은 어노테이션 값으로 컴파일되지 않는다).
 */
public final class KafkaConsumerGroup {

  public static final String BOOKING = "booking-group";
  public static final String SEAT = "seat-group";
  public static final String TICKET = "ticket-group";
  public static final String PAYMENT = "payment-group";
  public static final String DLT_MONITOR = "dlt-monitor-group";

  private KafkaConsumerGroup() {}
}

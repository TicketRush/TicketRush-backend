package com.ticketrush.shared.performance.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;
import java.time.LocalDate;
import java.time.LocalTime;

public record PerformanceCreatedEvent(
    Long performanceId,
    String title,
    Integer totalSeats,
    LocalDate showDate,
    LocalTime showTime,
    Long price)
    implements DomainEvent {

  public static final String TOPIC = "performance-events";
  public static final String EVENT_NAME = "PerformanceCreated";

  /**
   * 좌석 수 상한. 공연 등록 요청 검증({@code @Max})과 좌석 생성 시 clamp가 이 값을 공유한다.
   *
   * <p>두 값이 갈리면 검증을 통과한 등록이 좌석 쪽에서 조용히 잘린다 — 관리자는 등록 성공을 보는데 좌석은 모자란 상태가 되고, 어느 쪽이 맞는지 판정할 수단이 없다.
   * 제약이 곧 이 페이로드의 성질이라 이벤트에 둔다.
   *
   * <p>10,000인 근거는 부하테스트 시딩 규모(1만 석)다. 이 값을 올리려면 좌석맵 캐시의 Redis 상주량을 먼저 봐야 한다 — 좌석당 약 106B라
   * 10,000석이면 공연당 약 1.06MB이고, prod Redis는 {@code maxmemory 64mb} + {@code noeviction}이다.
   */
  public static final int MAX_TOTAL_SEATS = 10_000;

  @Override
  public String topic() {
    return TOPIC;
  }

  @Override
  public String key() {
    return String.valueOf(performanceId);
  }

  @Override
  public String aggregateId() {
    return String.valueOf(performanceId);
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

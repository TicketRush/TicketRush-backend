package com.ticketrush.boundedcontext.seat.app.support;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusChangedResponse;
import com.ticketrush.boundedcontext.seat.app.mapper.SeatMapper;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.global.constants.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class SeatStatusEventPublisher {

  private final SeatStatusEventSender seatStatusEventSender;
  private final SeatMapper seatMapper;

  /**
   * 경로별 Counter를 기동 시 전부 만들어 둔다(#520).
   *
   * <p>핫패스에서 {@code Counter.builder(...).register(...)}를 부르지 않으려는 것이다 — {@code
   * SeatStatusSseEventSender}가 거부 카운터에 대해 남긴 근거와 같다. 여기는 그쪽보다 조건이 나쁘다. {@link
   * SeatEventSource#SCHEDULER_FALLBACK}이 tick당 최대 2,000건을 한 번에 밀어 넣는데, 그 구간이 정확히 이 지표를 읽으려는 구간이라 계측
   * 비용이 관측 대상에 섞이면 안 된다.
   *
   * <p>미리 만들어 두면 <b>아직 한 번도 발행되지 않은 경로도 0으로 노출된다</b>는 이점이 따라온다. 지연 생성이면 "그 경로가 조용했다"와 "그 경로를 계측하지
   * 못하고 있다"가 그래프에서 구분되지 않는다.
   */
  private final Map<SeatEventSource, Counter> publishedCounters =
      new EnumMap<>(SeatEventSource.class);

  public SeatStatusEventPublisher(
      SeatStatusEventSender seatStatusEventSender,
      SeatMapper seatMapper,
      MeterRegistry meterRegistry) {
    this.seatStatusEventSender = seatStatusEventSender;
    this.seatMapper = seatMapper;

    for (SeatEventSource source : SeatEventSource.values()) {
      publishedCounters.put(
          source,
          Counter.builder(MetricNames.SEAT_SSE_EVENT_PUBLISHED)
              .tag(MetricNames.TAG_SOURCE, source.tagValue())
              .register(meterRegistry));
    }
  }

  /**
   * 좌석 상태 변경 이벤트를 커밋 이후에 발행한다.
   *
   * <p>{@code source}는 호출자만 아는 정보다 — 좌석 상태로 유도할 수 없다({@link SeatEventSource} 참고). 카운터는 <b>실제 send
   * 시점에</b> 올린다. 롤백된 트랜잭션의 발행을 세면 큐 도착과 어긋나 이 지표를 넣은 이유 자체가 무너진다({@code
   * SeatHoldUseCase.incrementSuccessAfterCommit}과 같은 선).
   */
  public void publishAfterCommit(Seat seat, SeatEventSource source) {
    SeatStatusChangedResponse event = seatMapper.toChangedResponse(seat);
    Counter published = publishedCounters.get(source);

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      published.increment();
      seatStatusEventSender.send(event);
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            published.increment();
            seatStatusEventSender.send(event);
          }
        });
  }
}

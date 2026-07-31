package com.ticketrush.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 대기열 지표(ADR 0009 "후속 작업" — 대기 인원·입장 허용률·상태 확인 RPS·폴링 주기).
 *
 * <p><b>명명 규약의 SSOT는 {@code common/.../global/constants/MetricNames} 다.</b> 게이트웨이는 {@code :common}
 * 에 의존하지 않으므로(common이 서블릿 스택을 끌고 와 WebFlux 게이트웨이를 깨뜨린다) 상수를 여기 복제하되 규약은 그대로 따른다 — dot 표기, {@code
 * result} 태그 키 재사용, <b>ID류를 태그로 쓰지 않는다</b>(카디널리티). 그래서 모든 지표는 공연별이 아니라 전체 합산이다.
 *
 * <p>게이트웨이의 {@code http.server.requests} 는 uri 라벨이 {@code /**}·{@code UNKNOWN} 으로 뭉개져(#402 실측 카디널리티
 * 4) 경로별로 볼 수 없다. 대기열 폴링을 관측할 유일한 수단이 이 커스텀 지표다.
 *
 * <p>모든 미터를 기동 시 등록한다. 핫패스에서 {@code builder().register()} 를 부르지 않기 위해서이기도 하고, 발생하지 않은 경로도 0으로 노출돼
 * "조용했다"와 "계측이 실패했다"가 구분되기 때문이다(seat-service {@code SeatStatusEventPublisher} 와 같은 선).
 */
@Component
public class WaitingRoomMetrics {

  private static final String QUEUE_WAITING = "ticketrush.queue.waiting";
  private static final String QUEUE_ADMISSION = "ticketrush.queue.admission";
  private static final String QUEUE_POLL_INTERVAL = "ticketrush.queue.poll.interval";
  private static final String QUEUE_ENTRY_TOKEN = "ticketrush.queue.entry.token";

  private static final String TAG_RESULT = "result";

  private static final String RESULT_ADMITTED = "admitted";
  private static final String RESULT_WAITING = "waiting";
  private static final String RESULT_UNAVAILABLE = "unavailable";
  private static final String RESULT_VALID = "valid";
  private static final String RESULT_MISSING = "missing";
  private static final String RESULT_INVALID = "invalid";

  /**
   * 가장 최근 조회된 공연의 대기 인원.
   *
   * <p>ID류 태그 금지 때문에 공연별로 쪼개지 않는다. 부하 회차는 단일 공연을 쓰므로(런북 §16) 이 값이 곧 그 공연의 대기 인원이다.
   */
  private final AtomicLong waitingGauge = new AtomicLong(0L);

  /** 서버가 마지막으로 지시한 폴링 주기(초). 이 다이얼이 실제로 돌고 있는지 보는 축이다. */
  private final AtomicLong pollIntervalGauge = new AtomicLong(0L);

  private final Counter admissionAdmitted;
  private final Counter admissionWaiting;
  private final Counter admissionUnavailable;

  private final Counter entryTokenValid;
  private final Counter entryTokenMissing;
  private final Counter entryTokenInvalid;
  private final Counter entryTokenUnavailable;

  public WaitingRoomMetrics(MeterRegistry meterRegistry) {
    Gauge.builder(QUEUE_WAITING, waitingGauge, AtomicLong::get).register(meterRegistry);
    Gauge.builder(QUEUE_POLL_INTERVAL, pollIntervalGauge, AtomicLong::get)
        .baseUnit("seconds")
        .register(meterRegistry);

    this.admissionAdmitted = counter(meterRegistry, QUEUE_ADMISSION, RESULT_ADMITTED);
    this.admissionWaiting = counter(meterRegistry, QUEUE_ADMISSION, RESULT_WAITING);
    // 상태 확인 RPS는 이 세 카운터의 합으로 읽는다 — 폴링 1회가 정확히 1증가라 별도 미터가 필요 없다.
    this.admissionUnavailable = counter(meterRegistry, QUEUE_ADMISSION, RESULT_UNAVAILABLE);

    this.entryTokenValid = counter(meterRegistry, QUEUE_ENTRY_TOKEN, RESULT_VALID);
    this.entryTokenMissing = counter(meterRegistry, QUEUE_ENTRY_TOKEN, RESULT_MISSING);
    this.entryTokenInvalid = counter(meterRegistry, QUEUE_ENTRY_TOKEN, RESULT_INVALID);
    // fail-closed(ADR 0008)가 실제로 발동했는지 보는 유일한 축. 거절(invalid)과 섞으면 장애 구간이 정상 차단으로 읽힌다.
    this.entryTokenUnavailable = counter(meterRegistry, QUEUE_ENTRY_TOKEN, RESULT_UNAVAILABLE);
  }

  private static Counter counter(MeterRegistry registry, String name, String result) {
    return Counter.builder(name).tag(TAG_RESULT, result).register(registry);
  }

  public void recordWaiting(long waiting, int pollSeconds) {
    waitingGauge.set(waiting);
    pollIntervalGauge.set(pollSeconds);
    admissionWaiting.increment();
  }

  public void recordAdmitted(long waiting, int pollSeconds) {
    waitingGauge.set(waiting);
    pollIntervalGauge.set(pollSeconds);
    admissionAdmitted.increment();
  }

  public void recordStatusUnavailable() {
    admissionUnavailable.increment();
  }

  public void recordEntryTokenValid() {
    entryTokenValid.increment();
  }

  public void recordEntryTokenMissing() {
    entryTokenMissing.increment();
  }

  public void recordEntryTokenInvalid() {
    entryTokenInvalid.increment();
  }

  public void recordEntryTokenUnavailable() {
    entryTokenUnavailable.increment();
  }
}

package com.ticketrush.boundedcontext.seat.out.repository;

import com.ticketrush.global.constants.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 좌석맵 조회 응답의 JSON 배열 문자열 캐시(#469).
 *
 * <p>DTO가 아니라 직렬화된 JSON을 값으로 두는 이유는 {@code SeatFacade#getPerformanceSeatMap} 참고. 모든 연산이
 * <b>fail-open</b>이다 — prod Redis는 {@code maxmemory 64mb} + {@code noeviction}(락 키 evict 방지,
 * deploy/docker-compose.prod.yml)이라 가득 차면 SET이 거절되고, Redis 다운·타임아웃(1s)도 언제든 가능하다. 캐시는 가용성 최적화일 뿐이므로
 * 어떤 실패도 조회 경로를 막지 않고 DB 폴백으로 흡수한다(performance-service {@code CacheConfig}의 CacheErrorHandler와 같은
 * 원칙, 수동 캐시라 여기서 직접 구현).
 */
@Slf4j
@Repository
public class SeatMapCacheRepository {

  // Redis 키 컨벤션 {도메인}:{엔티티}[:{식별자}] (docs/backend-convention.md §4).
  // seat:lock: 프리픽스가 아니므로 TTL 만료 이벤트가 SeatLockExpirationListener에 걸리지 않는다.
  private static final String SEAT_MAP_PREFIX = "seat:seat-map:";

  // 무효화는 SeatStatusEventPublisher가 담당하고, TTL은 evict 누락·cache-aside 경합(커밋 직전 스냅샷이
  // evict 직후 적재되는 창)의 stale 상한이다(performance-service PERFORMANCE_LIST_TTL과 같은 근거).
  // 값이 공연당 최대 221KB(2,080석)라 짧은 TTL이 Redis 상주량도 활성 공연 수준으로 묶는다.
  private static final Duration SEAT_MAP_TTL = Duration.ofSeconds(30);

  private final StringRedisTemplate redisTemplate;
  private final Counter hitCounter;
  private final Counter missCounter;
  private final Counter failureCounter;

  public SeatMapCacheRepository(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
    this.redisTemplate = redisTemplate;
    // 핫패스에서 builder().register()를 부르지 않도록 기동 시 등록한다(SeatStatusEventPublisher와 같은 선).
    this.hitCounter = cacheCounter(meterRegistry, MetricNames.RESULT_HIT);
    this.missCounter = cacheCounter(meterRegistry, MetricNames.RESULT_MISS);
    // 장애를 miss에 섞으면 측정 회차가 히트율을 읽을 때 "장애 구간"이 "미스 폭증"으로 오독된다.
    this.failureCounter = cacheCounter(meterRegistry, MetricNames.RESULT_FAILURE);
  }

  private static Counter cacheCounter(MeterRegistry meterRegistry, String result) {
    return Counter.builder(MetricNames.SEAT_MAP_CACHE)
        .tag(MetricNames.TAG_RESULT, result)
        .register(meterRegistry);
  }

  /** 히트 시 캐시된 JSON 배열 문자열, 미스·Redis 장애·손상 값이면 null. */
  public String get(Long performanceId) {
    String cached;
    try {
      cached = redisTemplate.opsForValue().get(SEAT_MAP_PREFIX + performanceId);
    } catch (RuntimeException e) {
      failureCounter.increment();
      log.warn("좌석맵 캐시 조회 실패 — 미스로 취급해 DB로 내려간다. performanceId: {}", performanceId, e);
      return null;
    }

    if (cached != null && !cached.startsWith("[")) {
      // 이 값은 파싱 없이 응답 본문에 그대로 스플라이스되므로(RawValue), 외부에서 손상된 값이 실리면
      // 응답 전체가 invalid JSON이 된다. 파싱 없는 최소 검증으로 걸러 버리고 DB로 내려간다.
      failureCounter.increment();
      log.warn("좌석맵 캐시 값이 JSON 배열 형태가 아니라 폐기한다. performanceId: {}", performanceId);
      evict(performanceId);
      return null;
    }

    (cached != null ? hitCounter : missCounter).increment();
    return cached;
  }

  public void set(Long performanceId, String seatMapJson) {
    try {
      redisTemplate.opsForValue().set(SEAT_MAP_PREFIX + performanceId, seatMapJson, SEAT_MAP_TTL);
    } catch (RuntimeException e) {
      // noeviction 상한 도달 시 SET이 OOM 응답으로 거절되는 경로가 정확히 여기다. 응답은 이미 손에 있으므로 삼킨다.
      log.warn("좌석맵 캐시 적재 실패 — 캐시 없이 진행한다. performanceId: {}", performanceId, e);
    }
  }

  public void evict(Long performanceId) {
    try {
      redisTemplate.delete(SEAT_MAP_PREFIX + performanceId);
    } catch (RuntimeException e) {
      // 실패 시 최대 TTL(30s)까지 stale일 수 있다. 오버셀 판정은 선점 로직 책임이고 캐시는 표시용 신선도만
      // 책임진다는 #469 전제로 수용한다.
      log.warn("좌석맵 캐시 무효화 실패 — TTL 만료로 수렴한다. performanceId: {}", performanceId, e);
    }
  }
}

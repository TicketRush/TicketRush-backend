package com.ticketrush.boundedcontext.seat.out.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.constants.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 좌석맵 캐시의 fail-open 계약(#469)을 고정한다. prod Redis는 maxmemory 64mb + noeviction이라 SET 거절이 정상 동작 범위이고,
 * 다운·타임아웃(1s)도 언제든 가능하다 — 어떤 실패도 조회 경로로 전파되면 안 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // opsForValue()는 케이스에 따라 안 불릴 수 있다
class SeatMapCacheRepositoryTest {

  private static final Long PERFORMANCE_ID = 1L;
  private static final String KEY = "seat:seat-map:1";
  private static final String JSON = "[{\"seat_id\":1}]";

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private SimpleMeterRegistry meterRegistry;
  private SeatMapCacheRepository seatMapCacheRepository;

  @BeforeEach
  void setUp() {
    given(redisTemplate.opsForValue()).willReturn(valueOperations);
    // 카운터를 생성자에서 등록하므로 실제 레지스트리로 손 조립한다(SeatStatusEventPublisherTest와 같은 패턴)
    meterRegistry = new SimpleMeterRegistry();
    seatMapCacheRepository = new SeatMapCacheRepository(redisTemplate, meterRegistry);
  }

  @Test
  @DisplayName("히트: 캐시된 JSON을 반환하고 hit 카운터가 오른다")
  void get_hit() {
    given(valueOperations.get(KEY)).willReturn(JSON);

    assertThat(seatMapCacheRepository.get(PERFORMANCE_ID)).isEqualTo(JSON);
    assertThat(cacheCount(MetricNames.RESULT_HIT)).isEqualTo(1.0);
    assertThat(cacheCount(MetricNames.RESULT_MISS)).isZero();
  }

  @Test
  @DisplayName("미스: null을 반환하고 miss 카운터가 오른다")
  void get_miss() {
    given(valueOperations.get(KEY)).willReturn(null);

    assertThat(seatMapCacheRepository.get(PERFORMANCE_ID)).isNull();
    assertThat(cacheCount(MetricNames.RESULT_MISS)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("조회 중 Redis 장애는 미스처럼 null을 주되 failure로 따로 센다 — 장애 구간이 미스 폭증으로 오독되지 않게")
  void get_redisFailure_countedAsFailure() {
    given(valueOperations.get(KEY)).willThrow(new RedisConnectionFailureException("down"));

    assertThat(seatMapCacheRepository.get(PERFORMANCE_ID)).isNull();
    assertThat(cacheCount(MetricNames.RESULT_FAILURE)).isEqualTo(1.0);
    assertThat(cacheCount(MetricNames.RESULT_MISS)).isZero();
  }

  @Test
  @DisplayName("JSON 배열 형태가 아닌 손상 값은 폐기하고 null을 반환한다 — RawValue 스플라이스가 invalid JSON을 내보내지 않게")
  void get_corruptValue_evictedAndTreatedAsMiss() {
    given(valueOperations.get(KEY)).willReturn("corrupt");

    assertThat(seatMapCacheRepository.get(PERFORMANCE_ID)).isNull();
    verify(redisTemplate).delete(KEY);
    assertThat(cacheCount(MetricNames.RESULT_FAILURE)).isEqualTo(1.0);
    assertThat(cacheCount(MetricNames.RESULT_HIT)).isZero();
  }

  @Test
  @DisplayName("적재는 TTL 30초로 저장한다")
  void set_storesWithTtl() {
    seatMapCacheRepository.set(PERFORMANCE_ID, JSON);

    verify(valueOperations).set(KEY, JSON, Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("적재 실패(noeviction SET 거절 등)를 삼킨다 — 응답은 이미 손에 있다")
  void set_failure_swallowed() {
    willThrow(new QueryTimeoutException("timeout"))
        .given(valueOperations)
        .set(anyString(), anyString(), any(Duration.class));

    assertThatCode(() -> seatMapCacheRepository.set(PERFORMANCE_ID, JSON))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("무효화 실패를 삼킨다 — stale은 TTL 30초로 수렴한다")
  void evict_failure_swallowed() {
    given(redisTemplate.delete(KEY)).willThrow(new RedisConnectionFailureException("down"));

    assertThatCode(() -> seatMapCacheRepository.evict(PERFORMANCE_ID)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("무효화는 공연 단위 키를 삭제한다")
  void evict_deletesPerformanceKey() {
    seatMapCacheRepository.evict(PERFORMANCE_ID);

    verify(redisTemplate).delete(KEY);
  }

  private double cacheCount(String result) {
    return meterRegistry
        .counter(MetricNames.SEAT_MAP_CACHE, MetricNames.TAG_RESULT, result)
        .count();
  }
}

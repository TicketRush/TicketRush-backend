package com.ticketrush.global.config;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListSlice;
import com.ticketrush.global.constants.CacheConstants;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * 공연 목록 조회 캐시 설정 (#177).
 *
 * <p>수동 {@code @Bean CacheManager} 대신 auto-configuration 커스터마이저를 사용한다 — test 프로파일의 {@code
 * spring.cache.type: none}이 그대로 동작해야 하기 때문.
 *
 * <p>{@code order = HIGHEST_PRECEDENCE}로 캐시 어드바이저를 트랜잭션 어드바이저 바깥에 고정한다 — 캐시 히트 시 불필요한 트랜잭션 진입을 막고,
 * {@code @CacheEvict}가 커밋 이후에 실행되어 커밋 전 stale 재적재 경합을 줄인다.
 */
@Slf4j
@Configuration
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class CacheConfig implements CachingConfigurer {

  /**
   * TTL은 evict 누락·경합(리더가 커밋 전 데이터를 evict 후 재적재하는 read-then-put)에 대비한 안전망. 갱신 경로 5곳(어드민 4종 + 예매 오픈
   * 스케줄러)이 모두 evict를 수행하므로 평상시 정합성은 evict가 보장한다.
   */
  private static final Duration PERFORMANCE_LIST_TTL = Duration.ofSeconds(30);

  /** clear 시 SCAN 배치 크기 — 기본 전략(KEYS)은 공유 Redis를 블로킹하므로 쓰지 않는다. */
  private static final int CLEAR_SCAN_BATCH_SIZE = 100;

  /**
   * spring-data-redis 4.0부터 캐시 쓰기·무효화가 기본 비동기(fire-and-forget)라, 뮤테이션 API가 응답한 뒤에도 잠깐 stale이 노출되고
   * evict 실패가 {@link CacheErrorHandler}에 잡히지 않는다. 무효화 즉시 반영(#177 완료 조건)을 위해 immediateWrites(동기 쓰기)로
   * 전환하고, 히트율 관측을 위해 통계 수집을 켠다.
   */
  @Bean
  public RedisCacheManagerBuilderCustomizer performanceListCacheCustomizer(
      RedisConnectionFactory connectionFactory) {
    return builder ->
        builder
            .cacheWriter(
                RedisCacheWriter.create(
                    connectionFactory,
                    config ->
                        config
                            .batchStrategy(BatchStrategies.scan(CLEAR_SCAN_BATCH_SIZE))
                            .immediateWrites()
                            .collectStatistics()))
            .withCacheConfiguration(
                CacheConstants.PERFORMANCE_LIST_CACHE,
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(PERFORMANCE_LIST_TTL)
                    .disableCachingNullValues()
                    .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                            new JacksonJsonRedisSerializer<>(PerformanceListSlice.class))));
  }

  /** Redis 장애 시 목록 API가 죽지 않도록 캐시 예외를 삼키고 미스로 취급한다(fail-open). */
  @Override
  public CacheErrorHandler errorHandler() {
    return new FailOpenCacheErrorHandler();
  }

  private static final class FailOpenCacheErrorHandler implements CacheErrorHandler {

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
      log.warn("캐시 조회 실패 — 미스로 취급합니다. cache={}, key={}", cache.getName(), key, exception);
    }

    @Override
    public void handleCachePutError(
        RuntimeException exception, Cache cache, Object key, Object value) {
      log.warn("캐시 저장 실패 — 무시합니다. cache={}, key={}", cache.getName(), key, exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
      log.warn("캐시 무효화 실패 — TTL 만료로 정리됩니다. cache={}, key={}", cache.getName(), key, exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
      log.warn("캐시 전체 무효화 실패 — TTL 만료로 정리됩니다. cache={}", cache.getName(), exception);
    }
  }
}

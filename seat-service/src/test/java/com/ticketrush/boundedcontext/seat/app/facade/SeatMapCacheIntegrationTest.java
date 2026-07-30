package com.ticketrush.boundedcontext.seat.app.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.seat.app.mapper.SeatMapper;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatGetSeatMapUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatHoldUseCase;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatMapCacheRepository;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.types.SeatStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 좌석맵 JSON 캐시(#469)를 실제 Redis(Testcontainers)로 검증한다 — 완료 조건 ①(히트 시 DB 미접근)과 ②(상태 변경 커밋 시 무효화)를 코드
 * 레벨에서 고정한다.
 *
 * <p>{@code @DataJpaTest} 슬라이스 + 수동 조립인 이유는 {@link SeatHoldConcurrencyTest}와 같다 — Kafka·스케줄러 오토컨피그가
 * 안 뜨므로 끄는 설정이 따로 필요 없다. 캐시(비트랜잭셔널)와 테스트 트랜잭션 롤백의 시점이 얽히지 않도록, 그리고 afterCommit 훅(무효화)이 실제로 돌도록 테스트
 * 트랜잭션을 끈다(performance-service PerformanceListCacheTest와 같은 판단).
 *
 * <p>Docker가 없는 환경에서는 컨테이너 기동 실패로 깨진다 — 의도된 fail-closed(#422 선례).
 */
@DataJpaTest
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeatMapCacheIntegrationTest {

  private static final Long PERFORMANCE_ID = 1L;
  private static final String CACHE_KEY = "seat:seat-map:" + PERFORMANCE_ID;

  /** prod(deploy/docker-compose.prod.yml)와 동일한 redis:7-alpine. */
  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Autowired private SeatRepository seatRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate redisTemplate;
  private SimpleMeterRegistry meterRegistry;
  private SeatMapCacheRepository seatMapCacheRepository;
  private SeatFacade seatFacade;
  private SeatHoldUseCase seatHoldUseCase;
  private TransactionTemplate transactionTemplate;

  @BeforeEach
  void setUp() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();

    meterRegistry = new SimpleMeterRegistry();
    seatMapCacheRepository = new SeatMapCacheRepository(redisTemplate, meterRegistry);
    transactionTemplate = new TransactionTemplate(transactionManager);

    // 프로덕션과 동일한 직렬화 규칙(snake_case·날짜 포맷)을 실물 JacksonConfig 커스터마이저로 재현한다.
    JsonMapper.Builder builder = JsonMapper.builder();
    new JacksonConfig().jacksonCustomizer().customize(builder);
    ObjectMapper objectMapper = builder.build();

    seatHoldUseCase =
        new SeatHoldUseCase(
            seatRepository,
            new SeatStatusEventPublisher(
                event -> {},
                Mappers.getMapper(SeatMapper.class),
                seatMapCacheRepository,
                meterRegistry),
            meterRegistry);

    // ponytail 선례(SeatHoldConcurrencyTest): 좌석맵 경로가 쓰는 의존성만 채우고 나머지는 null.
    seatFacade =
        new SeatFacade(
            null,
            new SeatGetSeatMapUseCase(seatRepository),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            seatMapCacheRepository,
            new JsonConverter(objectMapper));
  }

  @AfterEach
  void tearDown() {
    seatRepository.deleteAll();
    redisTemplate.delete(CACHE_KEY);
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  @DisplayName("첫 조회가 캐시를 적재하고, 두 번째 조회는 DB를 우회해 캐시에서 반환된다")
  void secondCall_servedFromCache_withoutDb() {
    // given — holdExpiredAt이 있는 좌석으로 snake_case·날짜 포맷까지 한 번에 고정한다
    seatRepository.save(availableSeat("A-1", null));
    seatRepository.save(heldSeat("A-2", LocalDateTime.of(2026, 8, 1, 12, 0, 0)));

    // when
    String first = seatFacade.getPerformanceSeatMap(PERFORMANCE_ID);

    // then — 기존 MVC 응답과 같은 직렬화 규칙(전역 snake_case, yyyy-MM-dd HH:mm:ss)
    assertThat(first).contains("\"seat_id\"").contains("\"seat_layout_id\"");
    assertThat(first)
        .contains("\"seat_status\":\"AVAILABLE\"")
        .contains("\"seat_status\":\"HOLD\"");
    assertThat(first).contains("\"hold_expired_at\":\"2026-08-01 12:00:00\"");
    assertThat(redisTemplate.hasKey(CACHE_KEY)).isTrue();

    // 캐시 무효화 경로를 우회해 DB만 비운다 — 두 번째 응답이 DB가 아니라 캐시에서 왔음을 증명
    seatRepository.deleteAll();

    String second = seatFacade.getPerformanceSeatMap(PERFORMANCE_ID);

    assertThat(second).isEqualTo(first);
    assertThat(cacheCount(MetricNames.RESULT_MISS)).isEqualTo(1.0);
    assertThat(cacheCount(MetricNames.RESULT_HIT)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("좌석 상태 변경이 커밋되면 캐시가 무효화되고, 다음 조회에 새 상태가 반영된다")
  void statusChangeCommit_evictsCache_nextReadSeesFreshState() {
    // given
    Long seatId = seatRepository.save(availableSeat("A-1", null)).getId();
    seatFacade.getPerformanceSeatMap(PERFORMANCE_ID);
    assertThat(redisTemplate.hasKey(CACHE_KEY)).isTrue();

    // when — 상태 전이 5곳이 전부 지나는 발행 지점(publishAfterCommit)이 afterCommit에서 evict 한다
    transactionTemplate.executeWithoutResult(
        status -> seatHoldUseCase.execute(seatId, LocalDateTime.now().plusMinutes(5), "BOOK-469"));

    // then — 완료 조건 ② "좌석 상태 변경 시 캐시가 무효화되어 stale 응답이 관측되지 않는다"
    assertThat(redisTemplate.hasKey(CACHE_KEY)).isFalse();
    assertThat(seatFacade.getPerformanceSeatMap(PERFORMANCE_ID))
        .contains("\"seat_status\":\"HOLD\"");
  }

  @Test
  @DisplayName("좌석이 없는 공연은 빈 배열을 반환하고 캐시에 적재하지 않는다")
  void emptySeatMap_notCached() {
    assertThat(seatFacade.getPerformanceSeatMap(PERFORMANCE_ID)).isEqualTo("[]");
    assertThat(redisTemplate.hasKey(CACHE_KEY)).isFalse();
  }

  private Seat availableSeat(String seatNumber, LocalDateTime holdExpiredAt) {
    return seat(seatNumber, SeatStatus.AVAILABLE, holdExpiredAt);
  }

  private Seat heldSeat(String seatNumber, LocalDateTime holdExpiredAt) {
    return seat(seatNumber, SeatStatus.HOLD, holdExpiredAt);
  }

  /* Seat는 seatLayoutId/performanceId가 plain Long이라 FK 없이 좌석만 단독 저장할 수 있다. */
  private Seat seat(String seatNumber, SeatStatus seatStatus, LocalDateTime holdExpiredAt) {
    return Seat.builder()
        .seatLayoutId(10L)
        .performanceId(PERFORMANCE_ID)
        .seatNumber(seatNumber)
        .seatStatus(seatStatus)
        .holdExpiredAt(holdExpiredAt)
        .build();
  }

  private double cacheCount(String result) {
    return meterRegistry
        .counter(MetricNames.SEAT_MAP_CACHE, MetricNames.TAG_RESULT, result)
        .count();
  }
}

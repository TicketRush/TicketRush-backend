package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceChangeStatusRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformancePatchRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceOpenBookingUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.constants.CacheConstants;
import com.ticketrush.global.dto.request.CursorPageRequest;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.util.S3UploadUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Slice;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 공연 목록 조회 Redis 캐싱(#177)을 실제 Redis(Testcontainers)로 검증한다. test 프로파일은 {@code spring.cache.type:
 * none}으로 캐시를 끄므로, 이 테스트만 redis로 오버라이드한다.
 *
 * <p>테스트 트랜잭션 롤백과 캐시(비트랜잭셔널)의 시점이 얽히지 않도록 {@code @Transactional}을 쓰지 않고 직접 정리한다.
 *
 * <p>Docker가 없는 환경에서는 컨테이너 기동 실패로 깨진다 — 의도된 fail-closed(payment #422 선례).
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
@Testcontainers
class PerformanceListCacheTest {

  /** prod(deploy/docker-compose.prod.yml)와 동일한 redis:7-alpine. */
  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private static final int PAGE_SIZE = 10;
  private static final String FIRST_PAGE_KEY =
      CacheConstants.PERFORMANCE_LIST_CACHE + "::size=" + PAGE_SIZE;

  @DynamicPropertySource
  static void redisCacheProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.cache.type", () -> "redis");
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    // 다른 테스트 컨텍스트와 공유 mem DB(testdb)를 create-drop으로 서로 갈아엎지 않도록 이 컨텍스트만 분리
    registry.add(
        "spring.datasource.url", () -> "jdbc:h2:mem:cachetestdb;MODE=MySQL;DB_CLOSE_DELAY=-1");
  }

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceFacade performanceFacade;
  @Autowired private PerformanceOpenBookingUseCase performanceOpenBookingUseCase;
  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private StringRedisTemplate redisTemplate;
  @Autowired private CacheManager cacheManager;

  @AfterEach
  void tearDown() {
    performanceRepository.deleteAll();
    Cache cache = cacheManager.getCache(CacheConstants.PERFORMANCE_LIST_CACHE);
    assertThat(cache).as("performance:list 캐시가 설정되어 있어야 한다").isNotNull();
    cache.clear();
  }

  @Test
  @DisplayName("무필터 첫 페이지 조회는 Redis에 캐시되고, 두 번째 호출은 캐시에서 반환된다")
  void unfilteredFirstPage_secondCallServedFromCache() {
    savePerformance(Genre.CONCERT, null);

    Slice<PerformanceListResponse> first = getUnfilteredFirstPage();

    assertThat(first.getContent()).hasSize(1);
    assertThat(redisTemplate.hasKey(FIRST_PAGE_KEY)).isTrue();

    // 캐시 무효화 경로를 우회해 DB에만 새 공연을 넣는다 — 두 번째 응답이 DB가 아닌 캐시에서 왔음을 증명
    savePerformance(Genre.MUSICAL, null);

    Slice<PerformanceListResponse> second = getUnfilteredFirstPage();

    assertThat(second.getContent()).hasSize(1);
    // record equals로 전 필드(LocalDate/LocalTime 포함) 직렬화 왕복 동등성까지 단언
    assertThat(second.getContent().getFirst()).isEqualTo(first.getContent().getFirst());
  }

  @Test
  @DisplayName("필터 또는 커서가 있는 조회는 캐시하지 않는다")
  void filteredOrCursorRequests_notCached() {
    Performance saved = savePerformance(Genre.CONCERT, null);

    performanceFacade.getPerformances(
        Genre.CONCERT, null, null, null, new CursorPageRequest(null, PAGE_SIZE));
    performanceFacade.getPerformances(null, 10000L, 90000L, null, firstPage());
    performanceFacade.getPerformances(
        null, null, null, null, new CursorPageRequest(saved.getId() + 1, PAGE_SIZE));

    assertThat(redisTemplate.keys(CacheConstants.PERFORMANCE_LIST_CACHE + "*")).isEmpty();
  }

  @Test
  @DisplayName("공연 등록 시 캐시가 무효화되어 다음 조회에 즉시 반영된다")
  void create_evictsCache() {
    savePerformance(Genre.CONCERT, null);
    warmCache();

    given(s3UploadUtils.uploadFile(any())).willReturn("https://example.com/file");
    performanceFacade.createPerformance(
        buildCreateRequest("신규 공연"), mockFile("mainImage"), mockFile("model3d"), null);

    assertThat(redisTemplate.hasKey(FIRST_PAGE_KEY)).isFalse();
    assertThat(getUnfilteredFirstPage().getContent())
        .hasSize(2)
        .anyMatch(p -> p.title().equals("신규 공연"));
  }

  @Test
  @DisplayName("공연 수정 시 캐시가 무효화되어 다음 조회에 즉시 반영된다")
  void patch_evictsCache() {
    Performance saved = savePerformance(Genre.CONCERT, null);
    warmCache();

    performanceFacade.patchPerformance(
        saved.getId(),
        new PerformancePatchRequest(
            "수정된 제목", null, null, null, null, null, null, null, null, null));

    assertThat(redisTemplate.hasKey(FIRST_PAGE_KEY)).isFalse();
    assertThat(getUnfilteredFirstPage().getContent()).anyMatch(p -> p.title().equals("수정된 제목"));
  }

  @Test
  @DisplayName("공연 상태 변경 시 캐시가 무효화되어 다음 조회에 즉시 반영된다")
  void changeStatus_evictsCache() {
    Performance saved = savePerformance(Genre.CONCERT, null);
    warmCache();

    performanceFacade.changePerformanceStatus(
        saved.getId(), new PerformanceChangeStatusRequest(PerformanceStatus.ON_SALE));

    assertThat(redisTemplate.hasKey(FIRST_PAGE_KEY)).isFalse();
    assertThat(getUnfilteredFirstPage().getContent())
        .anyMatch(p -> p.performanceStatus() == PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("공연 삭제 시 캐시가 무효화되어 다음 조회에 즉시 반영된다")
  void delete_evictsCache() {
    Performance saved = savePerformance(Genre.CONCERT, null);
    warmCache();

    performanceFacade.deletePerformance(saved.getId());

    assertThat(redisTemplate.hasKey(FIRST_PAGE_KEY)).isFalse();
    assertThat(getUnfilteredFirstPage().getContent()).isEmpty();
  }

  @Test
  @DisplayName("예매 오픈 스케줄러가 상태를 전환하면 캐시가 무효화된다")
  void openBooking_transitioned_evictsCache() {
    savePerformance(Genre.CONCERT, LocalDateTime.now().minusMinutes(1));
    warmCache();

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isEqualTo(1);
    assertThat(redisTemplate.hasKey(FIRST_PAGE_KEY)).isFalse();
    assertThat(getUnfilteredFirstPage().getContent())
        .anyMatch(p -> p.performanceStatus() == PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("예매 오픈 스케줄러가 전환한 공연이 없으면 캐시를 유지한다")
  void openBooking_nothingTransitioned_keepsCache() {
    savePerformance(Genre.CONCERT, LocalDateTime.now().plusHours(1));
    warmCache();

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isZero();
    assertThat(redisTemplate.hasKey(FIRST_PAGE_KEY)).isTrue();
  }

  private Slice<PerformanceListResponse> getUnfilteredFirstPage() {
    return performanceFacade.getPerformances(null, null, null, null, firstPage());
  }

  private void warmCache() {
    getUnfilteredFirstPage();
    assertThat(redisTemplate.hasKey(FIRST_PAGE_KEY)).isTrue();
  }

  private CursorPageRequest firstPage() {
    return new CursorPageRequest(null, PAGE_SIZE);
  }

  private Performance savePerformance(Genre genre, LocalDateTime bookingOpenAt) {
    return performanceRepository.save(
        Performance.builder()
            .title("공연명")
            .performer("출연진")
            .genre(genre)
            .description("설명")
            .showDate(LocalDate.now().plusDays(30))
            .showTime(LocalTime.of(19, 0))
            .durationMinutes(120)
            .price(50000L)
            .totalSeats(100)
            .address("서울")
            .bookingOpenAt(bookingOpenAt)
            .build());
  }

  private PerformanceCreateRequest buildCreateRequest(String title) {
    return PerformanceCreateRequest.builder()
        .title(title)
        .performer("출연진")
        .genre(Genre.CONCERT)
        .showDate(LocalDate.now().plusDays(30))
        .showTime(LocalTime.of(19, 0))
        .durationMinutes(120)
        .price(50000L)
        .totalSeats(100)
        .address("서울")
        .build();
  }

  private MockMultipartFile mockFile(String name) {
    return new MockMultipartFile(name, name + ".bin", "application/octet-stream", new byte[] {1});
  }
}

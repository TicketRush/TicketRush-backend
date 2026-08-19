package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceChangeStatusRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformancePatchRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceOpenBookingUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.apiclient.SeatRestClient;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.SeatCountsInfo;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.constants.CacheConstants;
import com.ticketrush.global.dto.request.CursorPageRequest;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.util.S3UploadUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

  /** 좌석 클라이언트는 반드시 대체한다 — test 프로파일에 URL 재정의가 없어 실제 호출이 나간다(#176). */
  @MockitoBean private SeatRestClient seatRestClient;

  @Autowired private PerformanceFacade performanceFacade;
  @Autowired private PerformanceOpenBookingUseCase performanceOpenBookingUseCase;
  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private StringRedisTemplate redisTemplate;
  @Autowired private CacheManager cacheManager;

  @BeforeEach
  void setUp() {
    given(seatRestClient.getSeatCounts(anyList())).willReturn(Map.of());
  }

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
    // 캐시를 타지 않으므로 좌석도 매 요청 새로 조회한다 — 좌석 합성이 캐시 대상 요청만의 기능이 아니다.
    verify(seatRestClient, times(3)).getSeatCounts(anyList());
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

  @Test
  @DisplayName("좌석 수도 목록과 함께 캐시된다 — 캐시가 살아 있는 동안은 좌석 서비스를 다시 부르지 않는다")
  void seatCounts_cachedTogetherWithList() {
    Performance saved = savePerformance(Genre.CONCERT, null);
    givenSeatCounts(saved.getId(), 500L, 300L);

    Slice<PerformanceListResponse> first = getUnfilteredFirstPage();
    assertThat(first.getContent().getFirst().remainingSeats()).isEqualTo(200L);

    // 그 사이 좌석이 더 팔렸더라도 캐시가 살아 있는 동안은 응답이 바뀌지 않는다
    givenSeatCounts(saved.getId(), 500L, 450L);

    Slice<PerformanceListResponse> second = getUnfilteredFirstPage();

    assertThat(second.getContent().getFirst().remainingSeats()).isEqualTo(200L);
    verify(seatRestClient, times(1)).getSeatCounts(anyList());
  }

  @Test
  @DisplayName("캐시가 무효화되면 좌석 수도 다시 조회해 최신값으로 바뀐다")
  void seatCounts_refreshedAfterEvict() {
    Performance saved = savePerformance(Genre.CONCERT, null);
    givenSeatCounts(saved.getId(), 500L, 300L);
    warmCache();

    givenSeatCounts(saved.getId(), 500L, 450L);
    performanceFacade.patchPerformance(
        saved.getId(),
        new PerformancePatchRequest(
            "수정된 제목", null, null, null, null, null, null, null, null, null));

    assertThat(getUnfilteredFirstPage().getContent().getFirst().remainingSeats()).isEqualTo(50L);
    verify(seatRestClient, times(2)).getSeatCounts(anyList());
  }

  /**
   * 좌석 수의 갱신 주기가 목록 캐시의 수명과 같음을 고정한다.
   *
   * <p>TTL이 실제로 만료되기를 기다리지 않는다 — TTL은 {@code CacheConfig}의 상수라 테스트에서 줄일 수 없고, 30초를 기다리는 테스트는 CI 비용이
   * 크다. 대신 Redis에 남은 수명을 직접 물어 좌석이 실린 항목도 같은 TTL을 달고 저장됨을 확인한다.
   */
  @Test
  @DisplayName("좌석 수가 실린 캐시 항목도 목록 캐시의 TTL을 그대로 따른다")
  void seatCounts_shareListCacheTtl() {
    Performance saved = savePerformance(Genre.CONCERT, null);
    givenSeatCounts(saved.getId(), 500L, 300L);
    warmCache();

    assertThat(getUnfilteredFirstPage().getContent().getFirst().totalSeats()).isEqualTo(500L);
    assertThat(redisTemplate.getExpire(FIRST_PAGE_KEY)).isPositive().isLessThanOrEqualTo(30L);
  }

  /**
   * 좌석 필드를 박스 타입({@code Long})으로 둔 결정을 고정한다.
   *
   * <p>롤링 배포 중에는 좌석 필드가 없는 <b>구버전 캐시 항목</b>을 새 코드가 읽는다. 원시 타입이었다면 그 순간 역직렬화가 깨지고(Jackson의 {@code
   * FAIL_ON_NULL_FOR_PRIMITIVES}가 기본 활성), 캐시 오류 핸들러가 그 예외를 삼켜 전 요청이 미스로 떨어진다 — 500은 나지 않지만 배포가 겹친
   * 동안 캐시가 통째로 무력해진다. 실제 저장 포맷에서 좌석 키만 덜어내 그 상황을 만든다.
   */
  @Test
  @DisplayName("좌석 필드가 없는 구버전 캐시 항목도 좌석만 비운 채 정상 복원된다")
  void legacyCacheEntryWithoutSeatFields_restoresWithNullSeats() {
    Performance saved = savePerformance(Genre.CONCERT, null);
    givenSeatCounts(saved.getId(), 500L, 300L);
    warmCache();

    String cached = redisTemplate.opsForValue().get(FIRST_PAGE_KEY);
    assertThat(cached).contains("totalSeats", "remainingSeats");

    String legacy = cached.replaceAll(",\"totalSeats\":[^,}]+,\"remainingSeats\":[^,}]+", "");
    assertThat(legacy).doesNotContain("totalSeats").doesNotContain("remainingSeats");
    redisTemplate.opsForValue().set(FIRST_PAGE_KEY, legacy);

    PerformanceListResponse row = getUnfilteredFirstPage().getContent().getFirst();

    // 원시 타입이었다면 여기서 캐시가 미스로 떨어져 좌석 수가 다시 채워진다(= null이 아니게 된다).
    assertThat(row.totalSeats()).isNull();
    assertThat(row.remainingSeats()).isNull();
    assertThat(row.title()).isEqualTo("공연명");
  }

  private void givenSeatCounts(Long performanceId, long totalCount, long soldCount) {
    given(seatRestClient.getSeatCounts(anyList()))
        .willReturn(
            Map.of(performanceId, new SeatCountsInfo(performanceId, totalCount, soldCount)));
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

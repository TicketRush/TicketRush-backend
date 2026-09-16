package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceGetListUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import com.ticketrush.boundedcontext.performance.domain.policy.ShowTimeCutoff;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.apiclient.SeatRestClient;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.dto.request.CursorPageRequest;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.util.S3UploadUtils;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Slice;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
@Transactional
class PerformanceGetListTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  /**
   * 좌석 클라이언트를 반드시 대체한다 (#176). test 프로파일에 {@code service.seat.url} 재정의가 없어 기본값 {@code
   * http://localhost:8086}이 그대로 살아 있고, 그대로 두면 이 테스트가 실제 HTTP 호출을 시도한다. fail-open이라 결과는 통과하겠지만 그 포트를
   * 쓰는 무언가가 떠 있으면 값이 새어 들어와 비결정적이 된다.
   */
  @MockitoBean private SeatRestClient seatRestClient;

  /**
   * 기준 시각을 고정한다 (#651). 정책을 그대로 두면 시드의 showDate·showTime이 실행 시각과 비교돼, KST 19시 이후나 자정 근처에 돌릴 때 결과가
   * 달라진다. {@code Clock} 빈을 통째로 바꾸지 않고 정책만 대체하는 이유는 auditing 등 다른 Clock 사용처를 건드리지 않기 위해서다.
   */
  @MockitoBean private PerformanceShowTimePolicy showTimePolicy;

  private static final ShowTimeCutoff CUTOFF =
      new ShowTimeCutoff(LocalDate.of(2026, 9, 15), LocalTime.of(19, 0));

  @Autowired private PerformanceGetListUseCase performanceGetListUseCase;
  @Autowired private PerformanceRepository performanceRepository;

  @BeforeEach
  void setUp() {
    given(seatRestClient.getSeatCounts(anyList())).willReturn(Map.of());
    given(showTimePolicy.cutoff()).willReturn(CUTOFF);
    performanceRepository.saveAll(
        List.of(
            buildPerformance(Genre.CONCERT, 30000L, null),
            buildPerformance(Genre.CONCERT, 50000L, PerformanceStatus.ON_SALE),
            buildPerformance(Genre.MUSICAL, 70000L, PerformanceStatus.ON_SALE),
            buildPerformance(Genre.JAZZ, 90000L, PerformanceStatus.CLOSED)));
  }

  private Slice<PerformanceListResponse> execute(
      Genre genre, Long minPrice, Long maxPrice, PerformanceStatus status, CursorPageRequest req) {
    return performanceGetListUseCase
        .execute(genre, minPrice, maxPrice, status, req)
        .toSlice(req.size());
  }

  private CursorPageRequest firstPage(int size) {
    return new CursorPageRequest(null, size);
  }

  @Test
  @DisplayName("장르를 지정하지 않으면 전체 공연 목록을 반환한다")
  void getAll_whenGenreIsNull() {
    Slice<PerformanceListResponse> result = execute(null, null, null, null, firstPage(10));

    assertThat(result.getContent()).hasSize(4);
    assertThat(result.hasNext()).isFalse();
  }

  @Test
  @DisplayName("목록은 performanceId 내림차순(최신 등록순)으로 정렬된다")
  void getAll_sortedByIdDesc() {
    Slice<PerformanceListResponse> result = execute(null, null, null, null, firstPage(10));

    assertThat(result.getContent())
        .isSortedAccordingTo(
            Comparator.comparing(PerformanceListResponse::performanceId).reversed());
  }

  @Test
  @DisplayName("장르를 지정하면 해당 장르 공연만 반환한다")
  void getByGenre_whenGenreIsGiven() {
    Slice<PerformanceListResponse> result = execute(Genre.CONCERT, null, null, null, firstPage(10));

    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getContent()).allMatch(p -> p.genre() == Genre.CONCERT);
  }

  @Test
  @DisplayName("첫 페이지는 size만큼 반환하고 남은 데이터가 있으면 hasNext가 true다")
  void cursorPaging_firstPage_hasNext() {
    Slice<PerformanceListResponse> firstPage = execute(null, null, null, null, firstPage(2));

    assertThat(firstPage.getContent()).hasSize(2);
    assertThat(firstPage.hasNext()).isTrue();
  }

  @Test
  @DisplayName("마지막 원소 id를 커서로 넘기면 중복·누락 없이 다음 페이지를 반환한다")
  void cursorPaging_nextPage_noOverlapOrGap() {
    Slice<PerformanceListResponse> firstPage = execute(null, null, null, null, firstPage(2));
    Long nextCursor = firstPage.getContent().getLast().performanceId();

    Slice<PerformanceListResponse> secondPage =
        execute(null, null, null, null, new CursorPageRequest(nextCursor, 2));

    List<Long> firstIds =
        firstPage.getContent().stream().map(PerformanceListResponse::performanceId).toList();
    List<Long> secondIds =
        secondPage.getContent().stream().map(PerformanceListResponse::performanceId).toList();

    assertThat(secondIds).hasSize(2).doesNotContainAnyElementsOf(firstIds);
    assertThat(secondIds).allMatch(id -> id < nextCursor);
    // 전체 4건 = 2 + 2, 정확히 size 배수여도 마지막 페이지의 hasNext는 false여야 한다
    assertThat(secondPage.hasNext()).isFalse();
  }

  @Test
  @DisplayName("잔여 데이터가 size보다 적으면 남은 만큼만 반환하고 hasNext가 false다")
  void cursorPaging_lastPage_partial() {
    Slice<PerformanceListResponse> firstPage = execute(null, null, null, null, firstPage(3));
    Long nextCursor = firstPage.getContent().getLast().performanceId();

    Slice<PerformanceListResponse> lastPage =
        execute(null, null, null, null, new CursorPageRequest(nextCursor, 3));

    assertThat(firstPage.hasNext()).isTrue();
    assertThat(lastPage.getContent()).hasSize(1);
    assertThat(lastPage.hasNext()).isFalse();
  }

  @Test
  @DisplayName("필터 조건과 커서 조건이 함께 적용된다")
  void cursorPaging_withFilter() {
    Slice<PerformanceListResponse> firstPage =
        execute(Genre.CONCERT, null, null, null, firstPage(1));
    Long nextCursor = firstPage.getContent().getLast().performanceId();

    Slice<PerformanceListResponse> secondPage =
        execute(Genre.CONCERT, null, null, null, new CursorPageRequest(nextCursor, 1));

    assertThat(firstPage.hasNext()).isTrue();
    assertThat(secondPage.getContent()).hasSize(1);
    assertThat(secondPage.hasNext()).isFalse();
    assertThat(secondPage.getContent()).allMatch(p -> p.genre() == Genre.CONCERT);
    assertThat(secondPage.getContent().getFirst().performanceId()).isLessThan(nextCursor);
  }

  @Test
  @DisplayName("최소 가격을 지정하면 해당 가격 이상의 공연만 반환한다")
  void filterByMinPrice() {
    Slice<PerformanceListResponse> result = execute(null, 60000L, null, null, firstPage(10));

    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getContent()).allMatch(p -> p.price() >= 60000L);
  }

  @Test
  @DisplayName("최대 가격을 지정하면 해당 가격 이하의 공연만 반환한다")
  void filterByMaxPrice() {
    Slice<PerformanceListResponse> result = execute(null, null, 50000L, null, firstPage(10));

    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getContent()).allMatch(p -> p.price() <= 50000L);
  }

  @Test
  @DisplayName("가격 범위를 지정하면 범위 내 공연만 반환한다")
  void filterByPriceRange() {
    Slice<PerformanceListResponse> result = execute(null, 40000L, 80000L, null, firstPage(10));

    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getContent()).allMatch(p -> p.price() >= 40000L && p.price() <= 80000L);
  }

  @Test
  @DisplayName("상태를 지정하면 해당 상태 공연만 반환한다")
  void filterByStatus() {
    Slice<PerformanceListResponse> result =
        execute(null, null, null, PerformanceStatus.ON_SALE, firstPage(10));

    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getContent())
        .allMatch(p -> p.performanceStatus() == PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("장르, 가격, 상태 복합 조건을 적용하면 모든 조건을 만족하는 공연만 반환한다")
  void filterByMultipleConditions() {
    Slice<PerformanceListResponse> result =
        execute(Genre.CONCERT, 40000L, 60000L, PerformanceStatus.ON_SALE, firstPage(10));

    assertThat(result.getContent()).hasSize(1);
    PerformanceListResponse only = result.getContent().get(0);
    assertThat(only.genre()).isEqualTo(Genre.CONCERT);
    assertThat(only.price()).isBetween(40000L, 60000L);
    assertThat(only.performanceStatus()).isEqualTo(PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("최소 가격이 최대 가격보다 크면 PERFORMANCE_INVALID_PRICE_RANGE 예외가 발생한다")
  void invalidPriceRange_throwsException() {
    assertThatThrownBy(() -> execute(null, 80000L, 40000L, null, firstPage(10)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.PERFORMANCE_INVALID_PRICE_RANGE);
  }

  // ---- 시작 시각이 지난 공연 제외 (#651) ----

  @Test
  @DisplayName("시작 날짜가 지난 공연은 상태가 ON_SALE이어도 목록에 실리지 않는다")
  void pastShowDate_excluded() {
    Performance past =
        saveShowAt(CUTOFF.date().minusDays(1), LocalTime.of(23, 59), PerformanceStatus.ON_SALE);

    List<Long> ids = idsOf(execute(null, null, null, null, firstPage(10)));

    assertThat(ids).hasSize(4).doesNotContain(past.getId());
  }

  @Test
  @DisplayName("같은 날 시작 시각이 지난 공연은 제외되고, 아직 오지 않은 공연은 실린다")
  void sameDay_excludedOnlyWhenTimePassed() {
    Performance before = saveShowAt(CUTOFF.date(), LocalTime.of(18, 59), PerformanceStatus.ON_SALE);
    Performance after = saveShowAt(CUTOFF.date(), LocalTime.of(19, 1), PerformanceStatus.ON_SALE);

    List<Long> ids = idsOf(execute(null, null, null, null, firstPage(10)));

    assertThat(ids).contains(after.getId()).doesNotContain(before.getId());
  }

  /** 정각은 "지남"이다. 벌크 전환 JPQL이 {@code <=}로 정각을 가져가므로, 여기서 정각을 실으면 두 조건이 겹친다. */
  @Test
  @DisplayName("시작 시각 정각인 공연은 지난 것으로 보아 제외된다")
  void exactStartTime_excluded() {
    Performance exact = saveShowAt(CUTOFF.date(), CUTOFF.time(), PerformanceStatus.ON_SALE);

    List<Long> ids = idsOf(execute(null, null, null, null, firstPage(10)));

    assertThat(ids).doesNotContain(exact.getId());
  }

  @Test
  @DisplayName("status 필터를 걸어도 시작 시각이 지난 공연은 제외된다")
  void pastShow_excludedEvenWithStatusFilter() {
    Performance past =
        saveShowAt(CUTOFF.date().minusDays(1), LocalTime.of(19, 0), PerformanceStatus.ON_SALE);

    List<Long> ids = idsOf(execute(null, null, null, PerformanceStatus.ON_SALE, firstPage(10)));

    assertThat(ids).hasSize(2).doesNotContain(past.getId());
  }

  @Test
  @DisplayName("hasNext는 지난 공연을 뺀 뒤의 건수로 판단한다")
  void hasNext_countsOnlyUpcomingShows() {
    // 4건(미래) + 1건(지난) = 5건이지만 목록 기준으로는 4건이라, size 4의 첫 페이지에 다음 페이지가 없어야 한다
    saveShowAt(CUTOFF.date().minusDays(1), LocalTime.of(19, 0), PerformanceStatus.ON_SALE);

    Slice<PerformanceListResponse> page = execute(null, null, null, null, firstPage(4));

    assertThat(page.getContent()).hasSize(4);
    assertThat(page.hasNext()).isFalse();
  }

  private Performance saveShowAt(LocalDate showDate, LocalTime showTime, PerformanceStatus status) {
    return performanceRepository.save(
        buildPerformance(Genre.CONCERT, 50000L, status, showDate, showTime));
  }

  private List<Long> idsOf(Slice<PerformanceListResponse> slice) {
    return slice.getContent().stream().map(PerformanceListResponse::performanceId).toList();
  }

  /** 기본 시드는 기준 시각보다 한 달 뒤라 제외 조건에 걸리지 않는다. */
  private Performance buildPerformance(Genre genre, Long price, PerformanceStatus targetStatus) {
    return buildPerformance(
        genre, price, targetStatus, CUTOFF.date().plusDays(30), LocalTime.of(19, 0));
  }

  private Performance buildPerformance(
      Genre genre,
      Long price,
      PerformanceStatus targetStatus,
      LocalDate showDate,
      LocalTime showTime) {
    Performance performance =
        Performance.builder()
            .title("공연명")
            .performer("가수")
            .genre(genre)
            .showDate(showDate)
            .showTime(showTime)
            .durationMinutes(120)
            .price(price)
            .totalSeats(100)
            .address("서울")
            .build();

    if (targetStatus == PerformanceStatus.ON_SALE) {
      performance.changeStatus(PerformanceStatus.ON_SALE);
    } else if (targetStatus == PerformanceStatus.CLOSED) {
      performance.changeStatus(PerformanceStatus.ON_SALE);
      performance.changeStatus(PerformanceStatus.CLOSED);
    } else if (targetStatus == PerformanceStatus.CANCELED) {
      performance.changeStatus(PerformanceStatus.CANCELED);
    }

    return performance;
  }
}

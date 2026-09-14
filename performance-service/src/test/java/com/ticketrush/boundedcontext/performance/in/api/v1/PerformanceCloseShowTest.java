package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceCloseShowUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import com.ticketrush.boundedcontext.performance.domain.policy.ShowTimeCutoff;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.util.S3UploadUtils;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공연 시작 시각이 지난 ON_SALE 공연의 CLOSED 자동 전환 (#651). {@code PerformanceOpenBookingTest}와 같은 구성이다.
 *
 * <p>기준 시각은 정책을 대체해 고정한다. Clock 빈을 통째로 바꾸지 않는 이유는 auditing 등 다른 사용처를 건드리지 않기 위해서다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
@Transactional
class PerformanceCloseShowTest {

  private static final ShowTimeCutoff CUTOFF =
      new ShowTimeCutoff(LocalDate.of(2026, 9, 15), LocalTime.of(19, 0));

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;
  @MockitoBean private PerformanceShowTimePolicy showTimePolicy;

  @Autowired private PerformanceCloseShowUseCase performanceCloseShowUseCase;
  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private EntityManager em;

  @BeforeEach
  void setUp() {
    given(showTimePolicy.cutoff()).willReturn(CUTOFF);
  }

  private Performance saveShowAt(LocalDate showDate, LocalTime showTime, PerformanceStatus status) {
    Performance performance =
        Performance.builder()
            .title("공연명")
            .performer("출연진")
            .genre(Genre.CONCERT)
            .description("설명")
            .showDate(showDate)
            .showTime(showTime)
            .durationMinutes(120)
            .price(50000L)
            .totalSeats(100)
            .address("서울")
            .build();

    if (status == PerformanceStatus.ON_SALE) {
      performance.changeStatus(PerformanceStatus.ON_SALE);
    } else if (status == PerformanceStatus.CLOSED) {
      performance.changeStatus(PerformanceStatus.ON_SALE);
      performance.changeStatus(PerformanceStatus.CLOSED);
    } else if (status == PerformanceStatus.CANCELED) {
      performance.changeStatus(PerformanceStatus.CANCELED);
    }

    Performance saved = performanceRepository.save(performance);
    em.flush();
    return saved;
  }

  private PerformanceStatus statusOf(Long performanceId) {
    em.flush();
    em.clear();
    return performanceRepository.findById(performanceId).orElseThrow().getPerformanceStatus();
  }

  @Test
  @DisplayName("시작 날짜가 지난 ON_SALE 공연은 CLOSED로 전환된다")
  void closeShow_pastDate_transitionsToClosed() {
    Performance past =
        saveShowAt(CUTOFF.date().minusDays(1), LocalTime.of(23, 59), PerformanceStatus.ON_SALE);

    int closedCount = performanceCloseShowUseCase.execute();

    assertThat(closedCount).isEqualTo(1);
    assertThat(statusOf(past.getId())).isEqualTo(PerformanceStatus.CLOSED);
  }

  @Test
  @DisplayName("같은 날이면 시작 시각이 지난 공연만 전환되고 아직 오지 않은 공연은 남는다")
  void closeShow_sameDay_transitionsOnlyWhenTimePassed() {
    Performance before = saveShowAt(CUTOFF.date(), LocalTime.of(18, 59), PerformanceStatus.ON_SALE);
    Performance after = saveShowAt(CUTOFF.date(), LocalTime.of(19, 1), PerformanceStatus.ON_SALE);

    int closedCount = performanceCloseShowUseCase.execute();

    assertThat(closedCount).isEqualTo(1);
    assertThat(statusOf(before.getId())).isEqualTo(PerformanceStatus.CLOSED);
    assertThat(statusOf(after.getId())).isEqualTo(PerformanceStatus.ON_SALE);
  }

  /** 정각은 "지남"이다. 목록이 정각을 제외하므로 여기서 정각을 닫지 않으면 "목록에 없는데 ON_SALE"인 창이 생긴다. */
  @Test
  @DisplayName("시작 시각 정각인 공연은 지난 것으로 보아 CLOSED로 전환된다")
  void closeShow_exactStartTime_transitionsToClosed() {
    Performance exact = saveShowAt(CUTOFF.date(), CUTOFF.time(), PerformanceStatus.ON_SALE);

    int closedCount = performanceCloseShowUseCase.execute();

    assertThat(closedCount).isEqualTo(1);
    assertThat(statusOf(exact.getId())).isEqualTo(PerformanceStatus.CLOSED);
  }

  @Test
  @DisplayName("시작 시각이 지나지 않은 ON_SALE 공연은 전환되지 않는다")
  void closeShow_futureShow_notTransitioned() {
    Performance future =
        saveShowAt(CUTOFF.date().plusDays(1), LocalTime.of(0, 0), PerformanceStatus.ON_SALE);

    int closedCount = performanceCloseShowUseCase.execute();

    assertThat(closedCount).isZero();
    assertThat(statusOf(future.getId())).isEqualTo(PerformanceStatus.ON_SALE);
  }

  /** 전이표에 UPCOMING→CLOSED가 없다. 이번 범위는 ON_SALE만 다루며, UPCOMING인 채 지난 공연은 후속 이슈다. */
  @Test
  @DisplayName("시작 시각이 지났어도 UPCOMING 공연은 전환 대상이 아니다")
  void closeShow_pastUpcoming_notTransitioned() {
    Performance upcoming =
        saveShowAt(CUTOFF.date().minusDays(1), LocalTime.of(19, 0), PerformanceStatus.UPCOMING);

    int closedCount = performanceCloseShowUseCase.execute();

    assertThat(closedCount).isZero();
    assertThat(statusOf(upcoming.getId())).isEqualTo(PerformanceStatus.UPCOMING);
  }

  @Test
  @DisplayName("어드민이 취소한(CANCELED) 공연은 시작 시각이 지나도 전환되지 않는다")
  void closeShow_canceled_notTransitioned() {
    Performance canceled =
        saveShowAt(CUTOFF.date().minusDays(1), LocalTime.of(19, 0), PerformanceStatus.CANCELED);

    int closedCount = performanceCloseShowUseCase.execute();

    assertThat(closedCount).isZero();
    assertThat(statusOf(canceled.getId())).isEqualTo(PerformanceStatus.CANCELED);
  }

  @Test
  @DisplayName("이미 CLOSED인 공연은 다시 세지 않는다")
  void closeShow_alreadyClosed_notCounted() {
    saveShowAt(CUTOFF.date().minusDays(1), LocalTime.of(19, 0), PerformanceStatus.CLOSED);

    int closedCount = performanceCloseShowUseCase.execute();

    assertThat(closedCount).isZero();
  }

  @Test
  @DisplayName("소프트 삭제된 공연은 시작 시각이 지나도 전환되지 않는다")
  void closeShow_softDeleted_notTransitioned() {
    Performance deleted =
        saveShowAt(CUTOFF.date().minusDays(1), LocalTime.of(19, 0), PerformanceStatus.ON_SALE);
    deleted.softDelete();
    em.flush();

    int closedCount = performanceCloseShowUseCase.execute();

    assertThat(closedCount).isZero();
  }

  /** 목록 조건과 벌크 전환 조건이 정확한 여집합임을 고정한다. 어느 한쪽의 부등호가 바뀌면 정각 공연이 양쪽에 다 들어가거나 어디에도 안 들어가 이 테스트가 깨진다. */
  @Test
  @DisplayName("같은 컷오프로 목록에 실리는 공연과 CLOSED로 전환되는 공연은 서로소이며 합치면 전체 ON_SALE이다")
  void closeShow_isExactComplementOfListCondition() {
    List<Performance> onSale =
        List.of(
            saveShowAt(CUTOFF.date().minusDays(1), LocalTime.of(19, 0), PerformanceStatus.ON_SALE),
            saveShowAt(CUTOFF.date(), LocalTime.of(18, 59, 59), PerformanceStatus.ON_SALE),
            saveShowAt(CUTOFF.date(), CUTOFF.time(), PerformanceStatus.ON_SALE),
            saveShowAt(CUTOFF.date(), LocalTime.of(19, 0, 1), PerformanceStatus.ON_SALE),
            saveShowAt(CUTOFF.date().plusDays(1), LocalTime.of(0, 0), PerformanceStatus.ON_SALE));
    Set<Long> allIds = onSale.stream().map(Performance::getId).collect(Collectors.toSet());

    Set<Long> listedIds =
        performanceRepository
            .findByFilters(null, null, null, PerformanceStatus.ON_SALE, null, 100, CUTOFF)
            .getContent()
            .stream()
            .map(Performance::getId)
            .collect(Collectors.toSet());

    int closedCount = performanceCloseShowUseCase.execute();

    Set<Long> closedIds =
        allIds.stream()
            .filter(id -> statusOf(id) == PerformanceStatus.CLOSED)
            .collect(Collectors.toSet());

    assertThat(closedCount).isEqualTo(3);
    assertThat(listedIds).hasSize(2).doesNotContainAnyElementsOf(closedIds);
    assertThat(closedIds).hasSize(3);
    assertThat(listedIds)
        .containsAll(allIds.stream().filter(id -> !closedIds.contains(id)).toList());
  }
}

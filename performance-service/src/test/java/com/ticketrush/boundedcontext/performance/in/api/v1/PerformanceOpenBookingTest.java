package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceClearBookingOpenAtUseCase;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceOpenBookingUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.types.PerformanceStatus;
import com.ticketrush.global.util.S3UploadUtils;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
 * 예매 오픈 시각 도래 공연의 ON_SALE 자동 전환 (#298). {@code PerformanceCloseShowTest}와 같은 구성이다.
 *
 * <p>기준 시각은 정책을 대체해 고정한다 (#653). 실행 시각의 {@code now()} 상대값을 쓰면 KST JVM(로컬)에서는 옛 코드도 통과해 회귀를 못 잡고,
 * 정책의 존이 무엇이든 "1분 전"은 도래로 보이므로 검증이 아니다. 고정값이면 UTC JVM·KST JVM 어디서 돌려도 결과가 같다. Clock 빈을 통째로 바꾸지 않는
 * 이유는 auditing 등 다른 사용처를 건드리지 않기 위해서다.
 *
 * <p>정책이 mock이라 {@code bookingOpenCutoff()}를 stub하지 않으면 null이 넘어가 어떤 행도 매칭되지 않는다. "전환되지 않는다" 케이스만
 * 있으면 그 누락이 조용히 통과하므로 양성 케이스(1건 전환)가 같은 클래스에 있어야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
@Transactional
class PerformanceOpenBookingTest {

  private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 9, 15, 19, 0, 0);

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;
  @MockitoBean private PerformanceShowTimePolicy showTimePolicy;

  @Autowired private PerformanceOpenBookingUseCase performanceOpenBookingUseCase;
  @Autowired private PerformanceClearBookingOpenAtUseCase performanceClearBookingOpenAtUseCase;
  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private EntityManager em;

  @BeforeEach
  void setUp() {
    given(showTimePolicy.bookingOpenCutoff()).willReturn(CUTOFF);
  }

  /** showDate는 CUTOFF 기준 미래로 둔다. 오픈 벌크는 시작 시각을 보지 않지만, 픽스처가 "아직 시작 전 공연"이라는 의도는 시각 고정과 함께 간다. */
  private Performance savePerformance(LocalDateTime bookingOpenAt) {
    return performanceRepository.save(
        Performance.builder()
            .title("공연명")
            .performer("출연진")
            .genre(Genre.CONCERT)
            .description("설명")
            .showDate(CUTOFF.toLocalDate().plusDays(30))
            .showTime(LocalTime.of(19, 0))
            .durationMinutes(120)
            .price(50000L)
            .totalSeats(100)
            .address("서울")
            .bookingOpenAt(bookingOpenAt)
            .build());
  }

  private Performance refetch(Long performanceId) {
    em.flush();
    em.clear();
    return performanceRepository.findById(performanceId).orElseThrow();
  }

  @Test
  @DisplayName("오픈 시각이 도래한 UPCOMING 공연은 ON_SALE로 전환된다")
  void openBooking_dueUpcoming_transitionsToOnSale() {
    Performance performance = savePerformance(CUTOFF.minusMinutes(1));

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isEqualTo(1);
    assertThat(refetch(performance.getId()).getPerformanceStatus())
        .isEqualTo(PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("오픈 시각이 아직 도래하지 않은 공연은 전환되지 않는다")
  void openBooking_futureOpenAt_notTransitioned() {
    Performance performance = savePerformance(CUTOFF.plusHours(1));

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isZero();
    assertThat(refetch(performance.getId()).getPerformanceStatus())
        .isEqualTo(PerformanceStatus.UPCOMING);
  }

  /** 정각에 몰리는 티켓 오픈의 존재 이유. 비교가 {@code <}이면 정각 주기에서 열리지 않고 다음 주기(10초 뒤)로 밀린다. */
  @Test
  @DisplayName("오픈 시각이 판정 기준과 정확히 같으면(정각) 전환된다")
  void openBooking_exactCutoff_transitionsToOnSale() {
    Performance performance = savePerformance(CUTOFF);

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isEqualTo(1);
    assertThat(refetch(performance.getId()).getPerformanceStatus())
        .isEqualTo(PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("오픈 시각이 판정 기준보다 1초 뒤면 전환되지 않는다")
  void openBooking_oneSecondAfterCutoff_notTransitioned() {
    Performance performance = savePerformance(CUTOFF.plusSeconds(1));

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isZero();
    assertThat(refetch(performance.getId()).getPerformanceStatus())
        .isEqualTo(PerformanceStatus.UPCOMING);
  }

  @Test
  @DisplayName("오픈 시각이 설정되지 않은 공연은 전환 대상이 아니다")
  void openBooking_nullOpenAt_notTransitioned() {
    Performance performance = savePerformance(null);

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isZero();
    assertThat(refetch(performance.getId()).getPerformanceStatus())
        .isEqualTo(PerformanceStatus.UPCOMING);
  }

  @Test
  @DisplayName("해제 API로 오픈 시각을 지운 공연은 시각이 도래했더라도 전환되지 않는다")
  void openBooking_clearedOpenAt_notTransitioned() {
    Performance performance = savePerformance(CUTOFF.minusMinutes(1));
    em.flush();

    performanceClearBookingOpenAtUseCase.execute(performance.getId());

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isZero();
    assertThat(refetch(performance.getId()).getPerformanceStatus())
        .isEqualTo(PerformanceStatus.UPCOMING);
  }

  @Test
  @DisplayName("어드민이 취소한(CANCELED) 공연은 오픈 시각이 도래해도 전환되지 않는다")
  void openBooking_canceled_notTransitioned() {
    Performance performance = savePerformance(CUTOFF.minusMinutes(1));
    performance.changeStatus(PerformanceStatus.CANCELED);
    em.flush();

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isZero();
    assertThat(refetch(performance.getId()).getPerformanceStatus())
        .isEqualTo(PerformanceStatus.CANCELED);
  }

  @Test
  @DisplayName("소프트 삭제된 공연은 오픈 시각이 도래해도 전환되지 않는다")
  void openBooking_softDeleted_notTransitioned() {
    Performance performance = savePerformance(CUTOFF.minusMinutes(1));
    performance.softDelete(LocalDateTime.now());
    em.flush();

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isZero();
  }
}

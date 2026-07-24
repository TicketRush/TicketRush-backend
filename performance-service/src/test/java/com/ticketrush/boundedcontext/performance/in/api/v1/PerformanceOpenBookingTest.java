package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceClearBookingOpenAtUseCase;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceOpenBookingUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.util.S3UploadUtils;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
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
class PerformanceOpenBookingTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceOpenBookingUseCase performanceOpenBookingUseCase;
  @Autowired private PerformanceClearBookingOpenAtUseCase performanceClearBookingOpenAtUseCase;
  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private EntityManager em;

  private Performance savePerformance(LocalDateTime bookingOpenAt) {
    return performanceRepository.save(
        Performance.builder()
            .title("공연명")
            .performer("출연진")
            .genre(Genre.CONCERT)
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

  private Performance refetch(Long performanceId) {
    em.flush();
    em.clear();
    return performanceRepository.findById(performanceId).orElseThrow();
  }

  @Test
  @DisplayName("오픈 시각이 도래한 UPCOMING 공연은 ON_SALE로 전환된다")
  void openBooking_dueUpcoming_transitionsToOnSale() {
    Performance performance = savePerformance(LocalDateTime.now().minusMinutes(1));

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isEqualTo(1);
    assertThat(refetch(performance.getId()).getPerformanceStatus())
        .isEqualTo(PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("오픈 시각이 아직 도래하지 않은 공연은 전환되지 않는다")
  void openBooking_futureOpenAt_notTransitioned() {
    Performance performance = savePerformance(LocalDateTime.now().plusHours(1));

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
    Performance performance = savePerformance(LocalDateTime.now().minusMinutes(1));
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
    Performance performance = savePerformance(LocalDateTime.now().minusMinutes(1));
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
    Performance performance = savePerformance(LocalDateTime.now().minusMinutes(1));
    performance.softDelete();
    em.flush();

    int openedCount = performanceOpenBookingUseCase.execute();

    assertThat(openedCount).isZero();
  }
}

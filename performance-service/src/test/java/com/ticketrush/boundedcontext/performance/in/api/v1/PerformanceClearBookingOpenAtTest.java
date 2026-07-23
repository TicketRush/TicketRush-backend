package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceClearBookingOpenAtUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
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
class PerformanceClearBookingOpenAtTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

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
  @DisplayName("설정된 예매 오픈 시각을 해제하면 null이 된다")
  void clearBookingOpenAt_success() {
    Performance performance = savePerformance(LocalDateTime.now().plusDays(1));

    performanceClearBookingOpenAtUseCase.execute(performance.getId());

    assertThat(refetch(performance.getId()).getBookingOpenAt()).isNull();
  }

  /**
   * 이 테스트는 H2에서 돌아 affected rows 의미론까지 검증하지는 못한다. 프로덕션(MySQL)에서 멱등성이 성립하는 근거는 updatedAt이 항상 갱신되는 것과
   * Connector/J가 기본적으로 matched rows를 반환하는 것 두 가지다. updatedAt 갱신을 빼는 리팩토링은 H2에서 초록불인 채 MySQL에서만 깨질 수
   * 있다.
   */
  @Test
  @DisplayName("이미 해제된 공연에 다시 해제를 요청해도 예외 없이 통과한다")
  void clearBookingOpenAt_alreadyNull_idempotent() {
    Performance performance = savePerformance(null);
    Long id = performance.getId();

    assertThatCode(() -> performanceClearBookingOpenAtUseCase.execute(id))
        .doesNotThrowAnyException();

    assertThat(refetch(id).getBookingOpenAt()).isNull();
  }

  @Test
  @DisplayName("이미 판매 중인 공연을 해제해도 상태는 ON_SALE로 유지된다")
  void clearBookingOpenAt_onSale_statusNotRolledBack() {
    Performance performance = savePerformance(LocalDateTime.now().minusMinutes(1));
    performance.changeStatus(PerformanceStatus.ON_SALE);
    em.flush();

    performanceClearBookingOpenAtUseCase.execute(performance.getId());

    Performance cleared = refetch(performance.getId());
    assertThat(cleared.getBookingOpenAt()).isNull();
    assertThat(cleared.getPerformanceStatus()).isEqualTo(PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("존재하지 않는 공연 ID로 해제 요청 시 예외 발생")
  void clearBookingOpenAt_notFound() {
    assertThatThrownBy(() -> performanceClearBookingOpenAtUseCase.execute(Long.MAX_VALUE))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorStatus.PERFORMANCE_NOT_FOUND.getMessage());
  }

  @Test
  @DisplayName("소프트 삭제된 공연에 해제 요청 시 예외 발생")
  void clearBookingOpenAt_softDeleted_notFound() {
    Performance performance = savePerformance(LocalDateTime.now().plusDays(1));
    Long id = performance.getId();
    performance.softDelete();
    em.flush();

    assertThatThrownBy(() -> performanceClearBookingOpenAtUseCase.execute(id))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorStatus.PERFORMANCE_NOT_FOUND.getMessage());
  }
}

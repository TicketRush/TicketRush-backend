package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceChangeStatusRequest;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceChangeStatusUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.util.S3UploadUtils;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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
class PerformanceChangeStatusTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceChangeStatusUseCase performanceChangeStatusUseCase;
  @Autowired private PerformanceRepository performanceRepository;

  private Performance savedPerformance(PerformanceStatus status) {
    Performance performance =
        Performance.builder()
            .title("테스트 공연")
            .performer("테스터")
            .genre(Genre.CONCERT)
            .description("설명")
            .showDate(LocalDate.now().plusDays(30))
            .showTime(LocalTime.of(19, 0))
            .durationMinutes(120)
            .price(50000L)
            .totalSeats(100)
            .address("서울")
            .build();

    // 실제 정책에 따라 단계적으로 전환
    switch (status) {
      case ON_SALE -> performance.changeStatus(PerformanceStatus.ON_SALE);
      case CLOSED -> {
        performance.changeStatus(PerformanceStatus.ON_SALE);
        performance.changeStatus(PerformanceStatus.CLOSED);
      }
      case CANCELED -> performance.changeStatus(PerformanceStatus.CANCELED);
      default -> {}
    }

    return performanceRepository.save(performance);
  }

  @Test
  @DisplayName("UPCOMING → ON_SALE 전환 성공")
  void upcomingToOnSale() {
    Performance performance = savedPerformance(PerformanceStatus.UPCOMING);

    performanceChangeStatusUseCase.execute(
        performance.getId(), new PerformanceChangeStatusRequest(PerformanceStatus.ON_SALE));

    Performance updated = performanceRepository.findById(performance.getId()).orElseThrow();
    assertThat(updated.getPerformanceStatus()).isEqualTo(PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("UPCOMING → CANCELED 전환 성공")
  void upcomingToCanceled() {
    Performance performance = savedPerformance(PerformanceStatus.UPCOMING);

    performanceChangeStatusUseCase.execute(
        performance.getId(), new PerformanceChangeStatusRequest(PerformanceStatus.CANCELED));

    Performance updated = performanceRepository.findById(performance.getId()).orElseThrow();
    assertThat(updated.getPerformanceStatus()).isEqualTo(PerformanceStatus.CANCELED);
  }

  @Test
  @DisplayName("ON_SALE → CLOSED 전환 성공")
  void onSaleToClosed() {
    Performance performance = savedPerformance(PerformanceStatus.ON_SALE);

    performanceChangeStatusUseCase.execute(
        performance.getId(), new PerformanceChangeStatusRequest(PerformanceStatus.CLOSED));

    Performance updated = performanceRepository.findById(performance.getId()).orElseThrow();
    assertThat(updated.getPerformanceStatus()).isEqualTo(PerformanceStatus.CLOSED);
  }

  @Test
  @DisplayName("CLOSED → ON_SALE 전환 불가")
  void closedToOnSaleIsNotAllowed() {
    Performance performance = savedPerformance(PerformanceStatus.CLOSED);

    assertThatThrownBy(
            () ->
                performanceChangeStatusUseCase.execute(
                    performance.getId(),
                    new PerformanceChangeStatusRequest(PerformanceStatus.ON_SALE)))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorStatus.PERFORMANCE_INVALID_STATUS_TRANSITION.getMessage());
  }

  @Test
  @DisplayName("CANCELED → 모든 상태 전환 불가")
  void canceledIsTerminalState() {
    Performance performance = savedPerformance(PerformanceStatus.CANCELED);

    for (PerformanceStatus target :
        List.of(PerformanceStatus.UPCOMING, PerformanceStatus.ON_SALE, PerformanceStatus.CLOSED)) {
      assertThatThrownBy(
              () ->
                  performanceChangeStatusUseCase.execute(
                      performance.getId(), new PerformanceChangeStatusRequest(target)))
          .isInstanceOf(BusinessException.class)
          .hasMessage(ErrorStatus.PERFORMANCE_INVALID_STATUS_TRANSITION.getMessage());
    }
  }

  @Test
  @DisplayName("존재하지 않는 공연 ID로 요청 시 예외 발생")
  void performanceNotFound() {
    assertThatThrownBy(
            () ->
                performanceChangeStatusUseCase.execute(
                    999L, new PerformanceChangeStatusRequest(PerformanceStatus.ON_SALE)))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorStatus.PERFORMANCE_NOT_FOUND.getMessage());
  }
}

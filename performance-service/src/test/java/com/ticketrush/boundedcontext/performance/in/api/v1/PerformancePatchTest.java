package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformancePatchRequest;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformancePatchUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.util.S3UploadUtils;
import java.time.LocalDate;
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
class PerformancePatchTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformancePatchUseCase performancePatchUseCase;
  @Autowired private PerformanceRepository performanceRepository;

  private Performance savePerformance() {
    return performanceRepository.save(
        Performance.builder()
            .title("원래 공연명")
            .performer("원래 출연진")
            .genre(Genre.CONCERT)
            .description("원래 설명")
            .showDate(LocalDate.now().plusDays(30))
            .showTime(LocalTime.of(19, 0))
            .durationMinutes(120)
            .price(50000L)
            .totalSeats(100)
            .address("서울")
            .build());
  }

  @Test
  @DisplayName("전체 텍스트 필드를 수정하면 DB에 반영된다")
  void patchAllFields_success() {
    Performance performance = savePerformance();

    performancePatchUseCase.execute(
        performance.getId(),
        new PerformancePatchRequest(
            "새로운 공연명",
            "새로운 출연진",
            Genre.MUSICAL,
            "새로운 설명",
            LocalDate.now().plusDays(60),
            LocalTime.of(20, 0),
            150,
            80000L,
            200,
            "부산"));

    Performance updated = performanceRepository.findById(performance.getId()).orElseThrow();
    assertThat(updated.getTitle()).isEqualTo("새로운 공연명");
    assertThat(updated.getPerformer()).isEqualTo("새로운 출연진");
    assertThat(updated.getGenre()).isEqualTo(Genre.MUSICAL);
    assertThat(updated.getDescription()).isEqualTo("새로운 설명");
    assertThat(updated.getDurationMinutes()).isEqualTo(150);
    assertThat(updated.getPrice()).isEqualTo(80000L);
    assertThat(updated.getTotalSeats()).isEqualTo(200);
    assertThat(updated.getAddress()).isEqualTo("부산");
  }

  @Test
  @DisplayName("null 필드는 수정하지 않고 기존 값을 유지한다")
  void patchPartialFields_nullFieldsUnchanged() {
    Performance performance = savePerformance();

    performancePatchUseCase.execute(
        performance.getId(),
        new PerformancePatchRequest(
            "새로운 공연명", null, null, null, null, null, null, null, null, null));

    Performance updated = performanceRepository.findById(performance.getId()).orElseThrow();
    assertThat(updated.getTitle()).isEqualTo("새로운 공연명");
    assertThat(updated.getPerformer()).isEqualTo("원래 출연진");
    assertThat(updated.getGenre()).isEqualTo(Genre.CONCERT);
    assertThat(updated.getDescription()).isEqualTo("원래 설명");
    assertThat(updated.getPrice()).isEqualTo(50000L);
  }

  @Test
  @DisplayName("존재하지 않는 공연 ID로 수정 요청 시 예외 발생")
  void patchPerformance_notFound() {
    PerformancePatchRequest request =
        new PerformancePatchRequest("새 제목", null, null, null, null, null, null, null, null, null);

    assertThatThrownBy(() -> performancePatchUseCase.execute(999L, request))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorStatus.PERFORMANCE_NOT_FOUND.getMessage());
  }
}

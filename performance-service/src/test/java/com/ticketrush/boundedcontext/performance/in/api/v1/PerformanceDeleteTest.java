package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceDeleteUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.util.S3UploadUtils;
import jakarta.persistence.EntityManager;
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
class PerformanceDeleteTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceDeleteUseCase performanceDeleteUseCase;
  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private EntityManager em;

  private Performance savePerformance() {
    return performanceRepository.save(
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
            .build());
  }

  @Test
  @DisplayName("공연 삭제 시 deleted_at이 설정되고 이후 조회에서 제외된다")
  void softDelete_success() {
    Performance performance = savePerformance();
    Long id = performance.getId();

    performanceDeleteUseCase.execute(id);

    // L1 캐시를 비워 @SQLRestriction 필터가 적용된 실제 쿼리로 검증
    em.flush();
    em.clear();

    assertThat(performanceRepository.findById(id)).isEmpty();
  }

  @Test
  @DisplayName("존재하지 않는 공연 ID로 삭제 요청 시 예외 발생")
  void deletePerformance_notFound() {
    assertThatThrownBy(() -> performanceDeleteUseCase.execute(999L))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorStatus.PERFORMANCE_NOT_FOUND.getMessage());
  }

  @Test
  @DisplayName("이미 삭제된 공연을 다시 삭제하면 예외 발생")
  void deleteAlreadyDeletedPerformance_notFound() {
    Performance performance = savePerformance();
    Long id = performance.getId();

    performanceDeleteUseCase.execute(id);

    em.flush();
    em.clear();

    assertThatThrownBy(() -> performanceDeleteUseCase.execute(id))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorStatus.PERFORMANCE_NOT_FOUND.getMessage());
  }
}

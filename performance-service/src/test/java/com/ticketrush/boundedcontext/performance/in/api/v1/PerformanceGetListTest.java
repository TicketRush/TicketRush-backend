package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceGetListUseCase;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

  @Autowired private PerformanceGetListUseCase performanceGetListUseCase;
  @Autowired private PerformanceRepository performanceRepository;

  @BeforeEach
  void setUp() {
    performanceRepository.saveAll(
        List.of(
            buildPerformance(Genre.CONCERT, 30000L, null),
            buildPerformance(Genre.CONCERT, 50000L, PerformanceStatus.ON_SALE),
            buildPerformance(Genre.MUSICAL, 70000L, PerformanceStatus.ON_SALE),
            buildPerformance(Genre.JAZZ, 90000L, PerformanceStatus.CLOSED)));
  }

  @Test
  @DisplayName("장르를 지정하지 않으면 전체 공연 목록을 반환한다")
  void getAll_whenGenreIsNull() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<PerformanceListResponse> result =
        performanceGetListUseCase.execute(null, null, null, null, pageable);
    assertThat(result.getTotalElements()).isEqualTo(4);
  }

  @Test
  @DisplayName("장르를 지정하면 해당 장르 공연만 반환한다")
  void getByGenre_whenGenreIsGiven() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<PerformanceListResponse> result =
        performanceGetListUseCase.execute(Genre.CONCERT, null, null, null, pageable);
    assertThat(result.getTotalElements()).isEqualTo(2);
    assertThat(result.getContent()).allMatch(p -> p.genre() == Genre.CONCERT);
  }

  @Test
  @DisplayName("페이지 사이즈에 따라 결과가 올바르게 분할된다")
  void pagination_splitsByPageSize() {
    Pageable firstPage = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt"));
    Pageable secondPage = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "createdAt"));

    Page<PerformanceListResponse> page1 =
        performanceGetListUseCase.execute(null, null, null, null, firstPage);
    Page<PerformanceListResponse> page2 =
        performanceGetListUseCase.execute(null, null, null, null, secondPage);

    assertThat(page1.getContent()).hasSize(2);
    assertThat(page2.getContent()).hasSize(2);
    assertThat(page1.getTotalPages()).isEqualTo(2);
  }

  @Test
  @DisplayName("최소 가격을 지정하면 해당 가격 이상의 공연만 반환한다")
  void filterByMinPrice() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<PerformanceListResponse> result =
        performanceGetListUseCase.execute(null, 60000L, null, null, pageable);
    assertThat(result.getTotalElements()).isEqualTo(2);
    assertThat(result.getContent()).allMatch(p -> p.price() >= 60000L);
  }

  @Test
  @DisplayName("최대 가격을 지정하면 해당 가격 이하의 공연만 반환한다")
  void filterByMaxPrice() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<PerformanceListResponse> result =
        performanceGetListUseCase.execute(null, null, 50000L, null, pageable);
    assertThat(result.getTotalElements()).isEqualTo(2);
    assertThat(result.getContent()).allMatch(p -> p.price() <= 50000L);
  }

  @Test
  @DisplayName("가격 범위를 지정하면 범위 내 공연만 반환한다")
  void filterByPriceRange() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<PerformanceListResponse> result =
        performanceGetListUseCase.execute(null, 40000L, 80000L, null, pageable);
    assertThat(result.getTotalElements()).isEqualTo(2);
    assertThat(result.getContent()).allMatch(p -> p.price() >= 40000L && p.price() <= 80000L);
  }

  @Test
  @DisplayName("상태를 지정하면 해당 상태 공연만 반환한다")
  void filterByStatus() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<PerformanceListResponse> result =
        performanceGetListUseCase.execute(null, null, null, PerformanceStatus.ON_SALE, pageable);
    assertThat(result.getTotalElements()).isEqualTo(2);
    assertThat(result.getContent())
        .allMatch(p -> p.performanceStatus() == PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("장르, 가격, 상태 복합 조건을 적용하면 모든 조건을 만족하는 공연만 반환한다")
  void filterByMultipleConditions() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<PerformanceListResponse> result =
        performanceGetListUseCase.execute(
            Genre.CONCERT, 40000L, 60000L, PerformanceStatus.ON_SALE, pageable);
    assertThat(result.getTotalElements()).isEqualTo(1);
    PerformanceListResponse only = result.getContent().get(0);
    assertThat(only.genre()).isEqualTo(Genre.CONCERT);
    assertThat(only.price()).isBetween(40000L, 60000L);
    assertThat(only.performanceStatus()).isEqualTo(PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("최소 가격이 최대 가격보다 크면 PERFORMANCE_INVALID_PRICE_RANGE 예외가 발생한다")
  void invalidPriceRange_throwsException() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
    assertThatThrownBy(
            () -> performanceGetListUseCase.execute(null, 80000L, 40000L, null, pageable))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.PERFORMANCE_INVALID_PRICE_RANGE);
  }

  private Performance buildPerformance(Genre genre, Long price, PerformanceStatus targetStatus) {
    Performance performance =
        Performance.builder()
            .title("공연명")
            .performer("가수")
            .genre(genre)
            .showDate(LocalDate.now())
            .showTime(LocalTime.of(19, 0))
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

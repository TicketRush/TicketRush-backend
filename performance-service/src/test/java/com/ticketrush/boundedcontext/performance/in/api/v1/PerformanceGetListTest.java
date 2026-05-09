package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceGetListUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
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
            buildPerformance(Genre.CONCERT),
            buildPerformance(Genre.CONCERT),
            buildPerformance(Genre.MUSICAL),
            buildPerformance(Genre.JAZZ)));
  }

  @Test
  @DisplayName("장르를 지정하지 않으면 전체 공연 목록을 반환한다")
  void getAll_whenGenreIsNull() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<PerformanceListResponse> result = performanceGetListUseCase.execute(null, pageable);
    assertThat(result.getTotalElements()).isEqualTo(4);
  }

  @Test
  @DisplayName("장르를 지정하면 해당 장르 공연만 반환한다")
  void getByGenre_whenGenreIsGiven() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<PerformanceListResponse> result =
        performanceGetListUseCase.execute(Genre.CONCERT, pageable);
    assertThat(result.getTotalElements()).isEqualTo(2);
    assertThat(result.getContent()).allMatch(p -> p.genre() == Genre.CONCERT);
  }

  @Test
  @DisplayName("페이지 사이즈에 따라 결과가 올바르게 분할된다")
  void pagination_splitsByPageSize() {
    Pageable firstPage = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt"));
    Pageable secondPage = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "createdAt"));

    Page<PerformanceListResponse> page1 = performanceGetListUseCase.execute(null, firstPage);
    Page<PerformanceListResponse> page2 = performanceGetListUseCase.execute(null, secondPage);

    assertThat(page1.getContent()).hasSize(2);
    assertThat(page2.getContent()).hasSize(2);
    assertThat(page1.getTotalPages()).isEqualTo(2);
  }

  private Performance buildPerformance(Genre genre) {
    return Performance.builder()
        .title("공연명")
        .performer("가수")
        .genre(genre)
        .showDate(LocalDate.now())
        .showTime(LocalTime.of(19, 0))
        .durationMinutes(120)
        .price(50000L)
        .totalSeats(100)
        .address("서울")
        .build();
  }
}

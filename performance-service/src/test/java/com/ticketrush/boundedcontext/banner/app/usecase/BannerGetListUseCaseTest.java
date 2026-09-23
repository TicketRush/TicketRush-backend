package com.ticketrush.boundedcontext.banner.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ticketrush.boundedcontext.banner.app.dto.response.BannerResponse;
import com.ticketrush.boundedcontext.banner.domain.entity.Banner;
import com.ticketrush.boundedcontext.banner.out.repository.BannerRepository;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.util.S3UploadUtils;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배너 목록 조회 테스트.
 *
 * <p>Banner Repository는 실제 H2 DB를 사용하여 노출 순서 정렬을 검증한다. 공연 Repository는 Mockito Bean으로 교체하여 배너에 연결된
 * 공연 제목, 소개, 날짜, 대표 이미지의 조합을 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
@Transactional
class BannerGetListUseCaseTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;
  @MockitoBean private PerformanceRepository performanceRepository;

  @Autowired private BannerGetListUseCase bannerGetListUseCase;
  @Autowired private BannerRepository bannerRepository;

  @Test
  @DisplayName("노출 순서 오름차순으로 반환한다")
  void execute_sortsByDisplayOrder() {
    Performance first =
        performance(
            101L, "첫째 공연", "첫째 소개", LocalDate.of(2026, 9, 1), "https://example.com/first.jpg");

    Performance second =
        performance(
            102L, "둘째 공연", "둘째 소개", LocalDate.of(2026, 9, 2), "https://example.com/second.jpg");

    Performance third =
        performance(
            103L, "셋째 공연", "셋째 소개", LocalDate.of(2026, 9, 3), "https://example.com/third.jpg");

    bannerRepository.saveAll(
        List.of(banner(103L, "셋째 소제목", 3), banner(101L, "첫째 소제목", 1), banner(102L, "둘째 소제목", 2)));

    given(performanceRepository.findAllById(List.of(101L, 102L, 103L)))
        .willReturn(List.of(first, second, third));

    List<BannerResponse> result = bannerGetListUseCase.execute();

    assertThat(result).extracting(BannerResponse::title).containsExactly("첫째 공연", "둘째 공연", "셋째 공연");

    assertThat(result).extracting(BannerResponse::order).containsExactly(1, 2, 3);
  }

  @Test
  @DisplayName("배너가 하나도 없으면 빈 목록을 반환한다")
  void execute_emptyWhenNoBanner() {
    assertThat(bannerGetListUseCase.execute()).isEmpty();
  }

  @Test
  @DisplayName("연결된 공연이 존재하지 않으면 해당 배너를 제외한다")
  void execute_excludesBannerWithoutPerformance() {
    Performance existingPerformance =
        performance(
            101L, "존재하는 공연", "공연 소개", LocalDate.of(2026, 9, 1), "https://example.com/main.jpg");

    Banner visible = bannerRepository.save(banner(101L, "노출 소제목", 1));

    bannerRepository.save(banner(999L, "죽은 링크", 2));

    given(performanceRepository.findAllById(List.of(101L, 999L)))
        .willReturn(List.of(existingPerformance));

    List<BannerResponse> result = bannerGetListUseCase.execute();

    assertThat(result).extracting(BannerResponse::id).containsExactly(visible.getId());
  }

  @Test
  @DisplayName("배너 소제목과 공연 정보를 조합하여 응답한다")
  void execute_combinesBannerAndPerformance() {
    Performance performance =
        performance(
            42L,
            "Summer Jazz Night",
            "세계적인 재즈 뮤지션과 함께하는 특별한 밤",
            LocalDate.of(2026, 9, 15),
            "https://example.com/summer-jazz.jpg");

    Banner savedBanner = bannerRepository.save(banner(42L, "여름밤의 재즈 향연", 1));

    given(performanceRepository.findAllById(List.of(42L))).willReturn(List.of(performance));

    BannerResponse response = bannerGetListUseCase.execute().getFirst();

    assertThat(response.id()).isEqualTo(savedBanner.getId());

    assertThat(response.performanceId()).isEqualTo(42L);

    assertThat(response.title()).isEqualTo("Summer Jazz Night");

    assertThat(response.subtitle()).isEqualTo("여름밤의 재즈 향연");

    assertThat(response.description()).isEqualTo("세계적인 재즈 뮤지션과 함께하는 특별한 밤");

    assertThat(response.date()).isEqualTo(LocalDate.of(2026, 9, 15));

    assertThat(response.imageUrl()).isEqualTo("https://example.com/summer-jazz.jpg");

    assertThat(response.order()).isEqualTo(1);
  }

  @Test
  @DisplayName("배너 소제목과 공연 선택 정보가 없어도 조회할 수 있다")
  void execute_allowsNullOptionalFields() {
    Performance performance = performance(42L, "제목만 있는 공연", null, LocalDate.of(2026, 9, 15), null);

    bannerRepository.save(banner(42L, null, 1));

    given(performanceRepository.findAllById(List.of(42L))).willReturn(List.of(performance));

    BannerResponse response = bannerGetListUseCase.execute().getFirst();

    assertThat(response.title()).isEqualTo("제목만 있는 공연");

    assertThat(response.subtitle()).isNull();
    assertThat(response.description()).isNull();

    assertThat(response.date()).isEqualTo(LocalDate.of(2026, 9, 15));

    assertThat(response.imageUrl()).isNull();
  }

  private Banner banner(Long performanceId, String subtitle, int displayOrder) {
    return Banner.builder()
        .performanceId(performanceId)
        .subtitle(subtitle)
        .displayOrder(displayOrder)
        .build();
  }

  private Performance performance(
      Long id, String title, String description, LocalDate showDate, String imageMainUrl) {
    Performance performance = mock(Performance.class);

    given(performance.getId()).willReturn(id);
    given(performance.getTitle()).willReturn(title);
    given(performance.getDescription()).willReturn(description);
    given(performance.getShowDate()).willReturn(showDate);
    given(performance.getImageMainUrl()).willReturn(imageMainUrl);

    return performance;
  }
}

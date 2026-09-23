package com.ticketrush.boundedcontext.performance.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.banner.app.dto.response.BannerResponse;
import com.ticketrush.boundedcontext.banner.app.usecase.BannerGetListUseCase;
import com.ticketrush.boundedcontext.banner.domain.entity.Banner;
import com.ticketrush.boundedcontext.banner.out.repository.BannerRepository;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformancePatchRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceDetailResponse;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
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
import org.springframework.mock.web.MockMultipartFile;
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
class PerformanceBannerIntegrationTest {

  private static final String MAIN_IMAGE_URL = "https://example.com/main.png";

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceCreateUseCase performanceCreateUseCase;
  @Autowired private PerformancePatchUseCase performancePatchUseCase;
  @Autowired private PerformanceGetDetailUseCase performanceGetDetailUseCase;
  @Autowired private PerformanceDeleteUseCase performanceDeleteUseCase;
  @Autowired private BannerGetListUseCase bannerGetListUseCase;
  @Autowired private BannerRepository bannerRepository;

  @BeforeEach
  void setUp() {
    given(s3UploadUtils.uploadFile(any(), any())).willReturn(MAIN_IMAGE_URL);
  }

  @Test
  @DisplayName("공연 등록 시 배너 노출을 선택하면 해당 공연의 배너가 생성된다")
  void createPerformance_displayOnBannerTrue_createsBanner() {
    Long performanceId = createPerformance("Summer Jazz Night", true, "  여름밤의 재즈 향연  ");

    Banner banner = bannerRepository.findByPerformanceId(performanceId).orElseThrow();

    assertThat(banner.getPerformanceId()).isEqualTo(performanceId);
    assertThat(banner.getSubtitle()).isEqualTo("여름밤의 재즈 향연");
    assertThat(banner.getDisplayOrder()).isEqualTo(1);
  }

  @Test
  @DisplayName("공연 등록 시 배너 노출을 false로 보내면 소제목이 있어도 배너를 생성하지 않는다")
  void createPerformance_displayOnBannerFalse_doesNotCreateBanner() {
    Long performanceId = createPerformance("일반 공연", false, "저장되면 안 되는 소제목");

    assertThat(bannerRepository.findByPerformanceId(performanceId)).isEmpty();
    assertThat(bannerRepository.count()).isZero();
  }

  @Test
  @DisplayName("공연 등록 시 배너 노출 여부를 생략하면 소제목이 있어도 배너를 생성하지 않는다")
  void createPerformance_displayOnBannerNull_doesNotCreateBanner() {
    Long performanceId = createPerformance("배너 여부 생략 공연", null, "무시되어야 하는 소제목");

    assertThat(bannerRepository.findByPerformanceId(performanceId)).isEmpty();
    assertThat(bannerRepository.count()).isZero();
  }

  @Test
  @DisplayName("공연 등록 시 배너 소제목이 공백이면 null로 저장한다")
  void createPerformance_blankBannerSubtitle_normalizesToNull() {
    Long performanceId = createPerformance("소제목 없는 배너 공연", true, "   ");

    Banner banner = bannerRepository.findByPerformanceId(performanceId).orElseThrow();

    assertThat(banner.getSubtitle()).isNull();
    assertThat(banner.getDisplayOrder()).isEqualTo(1);
  }

  @Test
  @DisplayName("배너로 등록한 공연은 상세 응답에 등록 여부와 소제목이 반환된다")
  void getPerformanceDetail_bannerRegistered_returnsBannerInfo() {
    Long performanceId = createPerformance("상세 응답 공연", true, "상세 응답 소제목");

    PerformanceDetailResponse response = performanceGetDetailUseCase.execute(performanceId);

    assertThat(response.displayOnBanner()).isTrue();
    assertThat(response.bannerSubtitle()).isEqualTo("상세 응답 소제목");
  }

  @Test
  @DisplayName("배너로 등록하지 않은 공연은 상세 응답에 false와 null이 반환된다")
  void getPerformanceDetail_bannerNotRegistered_returnsFalseAndNull() {
    Long performanceId = createPerformance("배너 미등록 공연", false, null);

    PerformanceDetailResponse response = performanceGetDetailUseCase.execute(performanceId);

    assertThat(response.displayOnBanner()).isFalse();
    assertThat(response.bannerSubtitle()).isNull();
  }

  @Test
  @DisplayName("배너 미등록 공연을 수정하면서 노출을 선택하면 배너가 새로 생성된다")
  void patchPerformance_falseToTrue_createsBanner() {
    Long performanceId = createPerformance("배너 전환 공연", false, null);

    performancePatchUseCase.execute(performanceId, bannerPatchRequest(true, "새로 등록한 배너 소제목"));

    Banner banner = bannerRepository.findByPerformanceId(performanceId).orElseThrow();

    assertThat(banner.getSubtitle()).isEqualTo("새로 등록한 배너 소제목");
    assertThat(banner.getDisplayOrder()).isEqualTo(1);
  }

  @Test
  @DisplayName("이미 배너로 등록된 공연을 true로 수정하면 기존 배너의 소제목이 변경된다")
  void patchPerformance_trueToTrue_updatesSubtitle() {
    Long performanceId = createPerformance("배너 수정 공연", true, "기존 소제목");

    Long originalBannerId =
        bannerRepository.findByPerformanceId(performanceId).orElseThrow().getId();

    performancePatchUseCase.execute(performanceId, bannerPatchRequest(true, "  변경된 소제목  "));

    Banner updated = bannerRepository.findByPerformanceId(performanceId).orElseThrow();

    assertThat(updated.getId()).isEqualTo(originalBannerId);
    assertThat(updated.getSubtitle()).isEqualTo("변경된 소제목");
    assertThat(updated.getDisplayOrder()).isEqualTo(1);
  }

  @Test
  @DisplayName("배너 노출을 true로 유지하면서 소제목을 생략하면 기존 소제목을 유지한다")
  void patchPerformance_trueWithNullSubtitle_keepsSubtitle() {
    Long performanceId = createPerformance("배너 소제목 유지 공연", true, "기존 소제목");

    performancePatchUseCase.execute(performanceId, bannerPatchRequest(true, null));

    Banner banner = bannerRepository.findByPerformanceId(performanceId).orElseThrow();

    assertThat(banner.getSubtitle()).isEqualTo("기존 소제목");
  }

  @Test
  @DisplayName("배너 노출을 true로 유지하면서 소제목을 비우면 기존 소제목을 삭제한다")
  void patchPerformance_trueWithBlankSubtitle_clearsSubtitle() {
    Long performanceId = createPerformance("배너 소제목 삭제 공연", true, "기존 소제목");

    performancePatchUseCase.execute(performanceId, bannerPatchRequest(true, "   "));

    Banner banner = bannerRepository.findByPerformanceId(performanceId).orElseThrow();

    assertThat(banner.getSubtitle()).isNull();
  }

  @Test
  @DisplayName("배너로 등록된 공연을 false로 수정하면 소제목 값과 관계없이 배너를 삭제한다")
  void patchPerformance_trueToFalse_deletesBanner() {
    Long performanceId = createPerformance("배너 삭제 공연", true, "삭제할 소제목");

    performancePatchUseCase.execute(performanceId, bannerPatchRequest(false, "이 소제목은 무시되어야 한다"));

    assertThat(bannerRepository.findByPerformanceId(performanceId)).isEmpty();

    PerformanceDetailResponse response = performanceGetDetailUseCase.execute(performanceId);

    assertThat(response.displayOnBanner()).isFalse();
    assertThat(response.bannerSubtitle()).isNull();
  }

  @Test
  @DisplayName("배너 미등록 공연을 false로 수정하면 소제목 값과 관계없이 아무 작업도 하지 않는다")
  void patchPerformance_falseToFalse_doesNothing() {
    Long performanceId = createPerformance("계속 배너 미등록 공연", false, null);

    performancePatchUseCase.execute(performanceId, bannerPatchRequest(false, "무시되어야 하는 소제목"));

    assertThat(bannerRepository.findByPerformanceId(performanceId)).isEmpty();
    assertThat(bannerRepository.count()).isZero();
  }

  @Test
  @DisplayName("배너 삭제 시 뒤에 있는 배너들의 노출 순서를 앞으로 당긴다")
  void patchPerformance_deleteBanner_compactsDisplayOrder() {
    Long firstPerformanceId = createPerformance("첫 번째 공연", true, "첫 번째 소제목");
    Long secondPerformanceId = createPerformance("두 번째 공연", true, "두 번째 소제목");
    Long thirdPerformanceId = createPerformance("세 번째 공연", true, "세 번째 소제목");

    assertThat(
            bannerRepository
                .findByPerformanceId(firstPerformanceId)
                .orElseThrow()
                .getDisplayOrder())
        .isEqualTo(1);

    assertThat(
            bannerRepository
                .findByPerformanceId(secondPerformanceId)
                .orElseThrow()
                .getDisplayOrder())
        .isEqualTo(2);

    assertThat(
            bannerRepository
                .findByPerformanceId(thirdPerformanceId)
                .orElseThrow()
                .getDisplayOrder())
        .isEqualTo(3);

    performancePatchUseCase.execute(secondPerformanceId, bannerPatchRequest(false, null));

    assertThat(bannerRepository.findByPerformanceId(secondPerformanceId)).isEmpty();

    assertThat(
            bannerRepository
                .findByPerformanceId(firstPerformanceId)
                .orElseThrow()
                .getDisplayOrder())
        .isEqualTo(1);

    assertThat(
            bannerRepository
                .findByPerformanceId(thirdPerformanceId)
                .orElseThrow()
                .getDisplayOrder())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("배너 삭제 후 새 배너를 등록하면 비어 있는 마지막 순서로 등록된다")
  void patchPerformance_deleteThenCreate_usesCompactedOrder() {
    createPerformance("첫 번째 공연", true, "첫 번째 소제목");

    Long secondPerformanceId = createPerformance("두 번째 공연", true, "두 번째 소제목");

    Long thirdPerformanceId = createPerformance("세 번째 공연", true, "세 번째 소제목");

    performancePatchUseCase.execute(secondPerformanceId, bannerPatchRequest(false, null));

    Long fourthPerformanceId = createPerformance("네 번째 공연", true, "네 번째 소제목");

    assertThat(
            bannerRepository
                .findByPerformanceId(thirdPerformanceId)
                .orElseThrow()
                .getDisplayOrder())
        .isEqualTo(2);

    assertThat(
            bannerRepository
                .findByPerformanceId(fourthPerformanceId)
                .orElseThrow()
                .getDisplayOrder())
        .isEqualTo(3);
  }

  @Test
  @DisplayName("배너에 등록된 공연을 삭제하면 연결된 배너도 삭제한다")
  void deletePerformance_bannerRegistered_removesBanner() {
    Long performanceId = createPerformance("삭제할 배너 공연", true, "삭제할 배너 소제목");

    assertThat(bannerRepository.findByPerformanceId(performanceId)).isPresent();
    assertThat(bannerRepository.count()).isEqualTo(1);

    performanceDeleteUseCase.execute(performanceId);

    assertThat(bannerRepository.findByPerformanceId(performanceId)).isEmpty();
    assertThat(bannerRepository.count()).isZero();
  }

  @Test
  @DisplayName("중간 배너의 공연을 삭제하면 뒤 배너의 노출 순서를 앞으로 당긴다")
  void deletePerformance_middleBanner_compactsDisplayOrder() {
    final Long firstPerformanceId = createPerformance("첫 번째 삭제 연동 공연", true, "첫 번째 소제목");

    final Long secondPerformanceId = createPerformance("두 번째 삭제 연동 공연", true, "두 번째 소제목");

    final Long thirdPerformanceId = createPerformance("세 번째 삭제 연동 공연", true, "세 번째 소제목");

    assertThat(
            bannerRepository
                .findByPerformanceId(thirdPerformanceId)
                .orElseThrow()
                .getDisplayOrder())
        .isEqualTo(3);

    performanceDeleteUseCase.execute(secondPerformanceId);

    assertThat(bannerRepository.findByPerformanceId(secondPerformanceId)).isEmpty();
    assertThat(bannerRepository.count()).isEqualTo(2);

    assertThat(
            bannerRepository
                .findByPerformanceId(firstPerformanceId)
                .orElseThrow()
                .getDisplayOrder())
        .isEqualTo(1);

    assertThat(
            bannerRepository
                .findByPerformanceId(thirdPerformanceId)
                .orElseThrow()
                .getDisplayOrder())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("수정 요청에서 배너 노출 여부를 생략하면 전달된 소제목도 무시하고 기존 배너를 유지한다")
  void patchPerformance_displayOnBannerNull_keepsExistingBanner() {
    Long performanceId = createPerformance("배너 유지 공연", true, "기존 소제목");

    Long originalBannerId =
        bannerRepository.findByPerformanceId(performanceId).orElseThrow().getId();

    performancePatchUseCase.execute(performanceId, bannerPatchRequest(null, "무시되어야 하는 소제목"));

    Banner banner = bannerRepository.findByPerformanceId(performanceId).orElseThrow();

    assertThat(banner.getId()).isEqualTo(originalBannerId);
    assertThat(banner.getSubtitle()).isEqualTo("기존 소제목");
    assertThat(banner.getDisplayOrder()).isEqualTo(1);
  }

  @Test
  @DisplayName("배너 미등록 공연 수정 시 노출 여부를 생략하면 소제목이 있어도 배너를 생성하지 않는다")
  void patchPerformance_displayOnBannerNull_doesNotCreateBanner() {
    Long performanceId = createPerformance("배너 미등록 유지 공연", false, null);

    performancePatchUseCase.execute(performanceId, bannerPatchRequest(null, "무시되어야 하는 소제목"));

    assertThat(bannerRepository.findByPerformanceId(performanceId)).isEmpty();
    assertThat(bannerRepository.count()).isZero();
  }

  @Test
  @DisplayName("배너가 3개이면 새로운 공연을 배너로 등록할 수 없다")
  void createPerformance_whenThreeBannersExist_throwsConflict() {
    createPerformance("첫 번째 공연", true, "첫 번째 소제목");
    createPerformance("두 번째 공연", true, "두 번째 소제목");
    createPerformance("세 번째 공연", true, "세 번째 소제목");

    assertThatThrownBy(() -> createPerformance("네 번째 공연", true, "네 번째 소제목"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorStatus.BANNER_LIMIT_EXCEEDED.getMessage());

    assertThat(bannerRepository.count()).isEqualTo(3);
  }

  @Test
  @DisplayName("배너가 3개여도 기존 배너의 소제목은 수정할 수 있다")
  void patchPerformance_whenThreeBannersExist_updatesExistingBanner() {
    Long firstPerformanceId = createPerformance("첫 번째 공연", true, "기존 첫 번째 소제목");

    createPerformance("두 번째 공연", true, "두 번째 소제목");
    createPerformance("세 번째 공연", true, "세 번째 소제목");

    performancePatchUseCase.execute(firstPerformanceId, bannerPatchRequest(true, "변경된 첫 번째 소제목"));

    Banner banner = bannerRepository.findByPerformanceId(firstPerformanceId).orElseThrow();

    assertThat(banner.getSubtitle()).isEqualTo("변경된 첫 번째 소제목");
    assertThat(banner.getDisplayOrder()).isEqualTo(1);
    assertThat(bannerRepository.count()).isEqualTo(3);
  }

  @Test
  @DisplayName("배너가 3개이면 배너 미등록 공연을 수정하여 새 배너로 등록할 수 없다")
  void patchPerformance_whenThreeBannersExist_cannotCreateNewBanner() {
    createPerformance("첫 번째 공연", true, "첫 번째 소제목");
    createPerformance("두 번째 공연", true, "두 번째 소제목");
    createPerformance("세 번째 공연", true, "세 번째 소제목");

    Long unregisteredPerformanceId = createPerformance("배너 미등록 공연", false, null);

    assertThatThrownBy(
            () ->
                performancePatchUseCase.execute(
                    unregisteredPerformanceId, bannerPatchRequest(true, "새 배너 소제목")))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorStatus.BANNER_LIMIT_EXCEEDED.getMessage());

    assertThat(bannerRepository.findByPerformanceId(unregisteredPerformanceId)).isEmpty();
    assertThat(bannerRepository.count()).isEqualTo(3);
  }

  @Test
  @DisplayName("배너 목록 조회 시 배너와 공연 정보를 조합하여 반환한다")
  void getBannerList_combinesBannerAndPerformance() {
    Long performanceId = createPerformance("Summer Jazz Night", true, "여름밤의 재즈 향연");

    List<BannerResponse> responses = bannerGetListUseCase.execute();

    assertThat(responses).hasSize(1);

    BannerResponse response = responses.getFirst();

    assertThat(response.performanceId()).isEqualTo(performanceId);
    assertThat(response.title()).isEqualTo("Summer Jazz Night");
    assertThat(response.subtitle()).isEqualTo("여름밤의 재즈 향연");
    assertThat(response.description()).isEqualTo("공연 설명");
    assertThat(response.date()).isEqualTo(LocalDate.now().plusDays(30));
    assertThat(response.imageUrl()).isEqualTo(MAIN_IMAGE_URL);
    assertThat(response.order()).isEqualTo(1);
  }

  @Test
  @DisplayName("배너 목록은 displayOrder 오름차순으로 반환된다")
  void getBannerList_returnsInDisplayOrder() {
    Long firstPerformanceId = createPerformance("첫 번째 공연", true, "첫 번째 소제목");

    Long secondPerformanceId = createPerformance("두 번째 공연", true, "두 번째 소제목");

    Long thirdPerformanceId = createPerformance("세 번째 공연", true, "세 번째 소제목");

    List<BannerResponse> responses = bannerGetListUseCase.execute();

    assertThat(responses)
        .extracting(BannerResponse::performanceId)
        .containsExactly(firstPerformanceId, secondPerformanceId, thirdPerformanceId);

    assertThat(responses).extracting(BannerResponse::order).containsExactly(1, 2, 3);
  }

  private Long createPerformance(String title, Boolean displayOnBanner, String bannerSubtitle) {

    PerformanceCreateRequest request =
        PerformanceCreateRequest.builder()
            .title(title)
            .performer("출연진")
            .genre(Genre.CONCERT)
            .description("공연 설명")
            .showDate(LocalDate.now().plusDays(30))
            .showTime(LocalTime.of(19, 0))
            .durationMinutes(120)
            .price(50000L)
            .totalSeats(100)
            .address("서울")
            .displayOnBanner(displayOnBanner)
            .bannerSubtitle(bannerSubtitle)
            .build();

    PerformanceCreateResponse response =
        performanceCreateUseCase.execute(request, mainImage(), null, null);

    return response.performanceId();
  }

  private PerformancePatchRequest bannerPatchRequest(
      Boolean displayOnBanner, String bannerSubtitle) {

    return new PerformancePatchRequest(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        displayOnBanner,
        bannerSubtitle);
  }

  private MockMultipartFile mainImage() {
    return new MockMultipartFile("mainImage", "main.png", "image/png", new byte[] {1});
  }
}

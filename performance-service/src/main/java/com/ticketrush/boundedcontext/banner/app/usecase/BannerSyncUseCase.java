package com.ticketrush.boundedcontext.banner.app.usecase;

import com.ticketrush.boundedcontext.banner.domain.entity.Banner;
import com.ticketrush.boundedcontext.banner.out.repository.BannerRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BannerSyncUseCase {

  private static final int MAX_BANNER_COUNT = 3;

  private final BannerRepository bannerRepository;

  /**
   * 공연 등록 전에 배너 등록 가능 여부를 확인한다.
   *
   * <p>배너가 이미 가득 찬 요청을 S3 파일 업로드 전에 거절하기 위한 사전 검사다. 실제 배너 생성 시점에도 다시 검사하여 동시 등록에 대비한다.
   *
   * @param displayOnBanner 배너 등록 여부
   */
  @Transactional(readOnly = true)
  public void validateCreateRequest(Boolean displayOnBanner) {
    if (!Boolean.TRUE.equals(displayOnBanner)) {
      return;
    }

    validateCapacity();
  }

  /**
   * 공연 등록과 함께 배너를 생성한다.
   *
   * <p>{@code displayOnBanner}가 null 또는 false이면 배너를 생성하지 않으며 소제목도 무시한다.
   *
   * @param performanceId 생성된 공연 ID
   * @param displayOnBanner 배너 등록 여부
   * @param bannerSubtitle 배너 전용 소제목
   */
  @Transactional
  public void createForPerformance(
      Long performanceId, Boolean displayOnBanner, String bannerSubtitle) {

    if (!Boolean.TRUE.equals(displayOnBanner)) {
      return;
    }

    createBanner(performanceId, bannerSubtitle);
  }

  /**
   * 공연 수정 요청에 맞춰 배너 상태를 동기화한다.
   *
   * <ul>
   *   <li>{@code displayOnBanner == null}: 배너 상태와 소제목 모두 유지
   *   <li>{@code displayOnBanner == true}: 배너 생성 또는 기존 배너 유지
   *   <li>{@code displayOnBanner == false}: 기존 배너 삭제
   *   <li>{@code displayOnBanner == true && bannerSubtitle == null}: 기존 소제목 유지
   *   <li>{@code displayOnBanner == true && bannerSubtitle가 공백}: 기존 소제목 삭제
   * </ul>
   *
   * <p>배너 체크 여부가 생략된 경우에는 {@code bannerSubtitle} 값이 함께 전달되더라도 무시한다. 프론트에서는 배너 체크 시에만 소제목 입력란을
   * 노출하지만, 백엔드도 잘못된 요청으로 인해 소제목만 변경되지 않도록 방어한다.
   *
   * @param performanceId 공연 ID
   * @param displayOnBanner 배너 등록 여부
   * @param bannerSubtitle 배너 전용 소제목
   */
  @Transactional
  public void synchronizeForPatch(
      Long performanceId, Boolean displayOnBanner, String bannerSubtitle) {

    /*
     * 배너 등록 여부가 생략되면 배너 관련 변경 요청 자체가 없는 것으로 처리한다.
     * bannerSubtitle이 전달되더라도 기존 배너와 소제목을 그대로 유지한다.
     */
    if (displayOnBanner == null) {
      return;
    }

    Optional<Banner> existingBanner = bannerRepository.findByPerformanceId(performanceId);

    if (Boolean.FALSE.equals(displayOnBanner)) {
      existingBanner.ifPresent(this::deleteAndReorder);
      return;
    }

    if (existingBanner.isPresent()) {
      /*
       * null은 기존 소제목 유지다.
       * 빈 문자열이나 공백 문자열은 Banner.updateSubtitle()에서 null로 정규화된다.
       */
      if (bannerSubtitle != null) {
        existingBanner.get().updateSubtitle(bannerSubtitle);
      }
      return;
    }

    createBanner(performanceId, bannerSubtitle);
  }

  /**
   * 공연 삭제 시 연결된 배너를 함께 삭제하고 노출 순서를 재정렬한다.
   *
   * @param performanceId 삭제할 공연 ID
   */
  @Transactional
  public void removeForPerformance(Long performanceId) {
    bannerRepository.findByPerformanceId(performanceId).ifPresent(this::deleteAndReorder);
  }

  private void createBanner(Long performanceId, String bannerSubtitle) {
    if (bannerRepository.existsByPerformanceId(performanceId)) {
      throw new BusinessException(ErrorStatus.BANNER_REGISTRATION_CONFLICT);
    }

    validateCapacity();

    int displayOrder = Math.toIntExact(bannerRepository.count() + 1L);

    Banner banner =
        Banner.builder()
            .performanceId(performanceId)
            .subtitle(bannerSubtitle)
            .displayOrder(displayOrder)
            .build();

    try {
      bannerRepository.saveAndFlush(banner);
    } catch (DataIntegrityViolationException exception) {
      /*
       * 동시 등록으로 performance_id 또는 display_order UNIQUE 제약이 충돌하면
       * DB 예외를 외부에 그대로 노출하지 않고 배너 등록 충돌로 변환한다.
       */
      throw new BusinessException(ErrorStatus.BANNER_REGISTRATION_CONFLICT);
    }
  }

  private void validateCapacity() {
    if (bannerRepository.count() >= MAX_BANNER_COUNT) {
      throw new BusinessException(ErrorStatus.BANNER_LIMIT_EXCEEDED);
    }
  }

  private void deleteAndReorder(Banner banner) {
    int deletedOrder = banner.getDisplayOrder();

    bannerRepository.delete(banner);

    /*
     * DELETE가 실제 DB에 반영되기 전에 뒤 배너의 순서를 변경하면
     * display_order UNIQUE 제약과 충돌할 수 있으므로 먼저 flush한다.
     */
    bannerRepository.flush();

    List<Banner> laterBanners =
        bannerRepository.findByDisplayOrderGreaterThanOrderByDisplayOrderAscIdAsc(deletedOrder);

    laterBanners.forEach(
        laterBanner -> laterBanner.changeDisplayOrder(laterBanner.getDisplayOrder() - 1));
  }
}

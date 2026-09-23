package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.banner.app.usecase.BannerSyncUseCase;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.mapper.PerformanceMapper;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.constants.CacheConstants;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.util.FileKind;
import com.ticketrush.global.util.S3UploadUtils;
import com.ticketrush.shared.performance.event.PerformanceCreatedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class PerformanceCreateUseCase {

  private final S3UploadUtils s3UploadUtils;
  private final PerformanceRepository performanceRepository;
  private final PerformanceMapper performanceMapper;
  private final EventPublisher eventPublisher;
  private final BannerSyncUseCase bannerSyncUseCase;

  /**
   * 공연 정보와 파일들을 받아 S3 업로드 후 DB에 저장한다.
   *
   * <p>배너 등록을 요청한 경우 파일 업로드 전에 배너 등록 가능 여부를 확인한다. 공연 저장 후에는 생성된 공연 ID를 사용해 배너를 같은 트랜잭션 안에서 생성한다.
   *
   * <p>{@code model3d}는 #650부터 선택이다. 캐릭터는 완성 GLB 대신 {@code characterConfig} JSON으로 저장하므로 3D 모델 파일은
   * 있을 때만 검증·업로드하고, 없으면 {@code image3dUrl}을 null로 둔다. 비어 있는 파트(0바이트)는 보내지 않은 것으로 본다.
   */
  @CacheEvict(cacheNames = CacheConstants.PERFORMANCE_LIST_CACHE, allEntries = true)
  @Transactional
  public PerformanceCreateResponse execute(
      PerformanceCreateRequest request,
      MultipartFile mainImage,
      MultipartFile model3d,
      List<MultipartFile> gallery) {
    /*
     * 배너가 이미 3개인 명백한 실패 요청은 S3 파일을 업로드하기 전에 거절한다.
     * 실제 생성 시점에도 다시 검사한다.
     */
    bannerSyncUseCase.validateCreateRequest(request.displayOnBanner());

    validateFiles(mainImage, model3d, gallery);

    String mainImageUrl = s3UploadUtils.uploadFile(mainImage, FileKind.MAIN_IMAGE);

    String model3dUrl =
        hasFile(model3d) ? s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D) : null;

    List<String> galleryUrls =
        gallery != null
            ? gallery.stream()
                .map(file -> s3UploadUtils.uploadFile(file, FileKind.GALLERY))
                .toList()
            : List.of();

    Performance performance = performanceMapper.toEntity(request);

    performance.updateUrls(mainImageUrl, model3dUrl, galleryUrls);

    Performance savedPerformance = performanceRepository.save(performance);

    /*
     * 공연 저장과 배너 저장은 같은 DB 트랜잭션에 참여한다.
     * 배너 저장이 실패하면 공연 저장도 롤백된다.
     */
    bannerSyncUseCase.createForPerformance(
        savedPerformance.getId(), request.displayOnBanner(), request.bannerSubtitle());

    eventPublisher.publish(
        new PerformanceCreatedEvent(
            savedPerformance.getId(),
            savedPerformance.getTitle(),
            savedPerformance.getTotalSeats(),
            savedPerformance.getShowDate(),
            savedPerformance.getShowTime(),
            savedPerformance.getPrice()));

    return performanceMapper.toCreateResponse(savedPerformance);
  }

  private void validateFiles(
      MultipartFile mainImage, MultipartFile model3d, List<MultipartFile> gallery) {
    if (mainImage == null || mainImage.isEmpty()) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_MAIN_IMAGE_MISSING);
    }

    if (gallery != null && gallery.size() > FileKind.GALLERY_MAX_COUNT) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_GALLERY_LIMIT_EXCEEDED);
    }

    FileKind.MAIN_IMAGE.validate(mainImage);

    if (hasFile(model3d)) {
      FileKind.MODEL_3D.validate(model3d);
    }

    if (gallery != null) {
      gallery.forEach(FileKind.GALLERY::validate);
    }
  }

  private static boolean hasFile(MultipartFile file) {
    return file != null && !file.isEmpty();
  }
}

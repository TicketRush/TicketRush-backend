package com.ticketrush.boundedcontext.performance.app.usecase;

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

  /** 공연 정보와 파일들을 받아 S3 업로드 후 DB에 저장 */
  @CacheEvict(cacheNames = CacheConstants.PERFORMANCE_LIST_CACHE, allEntries = true)
  @Transactional
  public PerformanceCreateResponse execute(
      PerformanceCreateRequest request,
      MultipartFile mainImage,
      MultipartFile model3d,
      List<MultipartFile> gallery) {

    validateFiles(mainImage, model3d, gallery);

    String mainImageUrl = s3UploadUtils.uploadFile(mainImage, FileKind.MAIN_IMAGE);
    String model3dUrl = s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D);
    List<String> galleryUrls =
        (gallery != null)
            ? gallery.stream()
                .map(file -> s3UploadUtils.uploadFile(file, FileKind.GALLERY))
                .toList()
            : List.of();

    Performance performance = performanceMapper.toEntity(request);

    performance.updateUrls(mainImageUrl, model3dUrl, galleryUrls);

    Performance savedPerformance = performanceRepository.save(performance);

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

    if (model3d == null || model3d.isEmpty()) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_MODEL_3D_MISSING);
    }

    if (gallery != null && gallery.size() > 3) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_GALLERY_LIMIT_EXCEEDED);
    }

    /*
     * 파트별 확장자·크기 검사는 업로드를 시작하기 전에 전부 끝낸다(#636).
     *
     * 업로드 직전에 파트마다 검사하면, mainImage를 올린 뒤 model3d에서 거절되는 순서라 거절될 요청마다 S3에 정리해야 할
     * 객체가 생긴다. 여기서 먼저 걸러내면 잘못된 요청은 업로드를 한 건도 시작하지 않는다.
     *
     * 위의 존재 여부 검사를 먼저 두는 이유는 "메인 이미지는 필수입니다" 같은 파트별 메시지를 유지하기 위해서다 —
     * FileKind.validate는 빈 파일을 파트 구분 없이 FILE_EMPTY로 처리한다.
     */
    FileKind.MAIN_IMAGE.validate(mainImage);
    FileKind.MODEL_3D.validate(model3d);

    if (gallery != null) {
      gallery.forEach(FileKind.GALLERY::validate);
    }
  }
}

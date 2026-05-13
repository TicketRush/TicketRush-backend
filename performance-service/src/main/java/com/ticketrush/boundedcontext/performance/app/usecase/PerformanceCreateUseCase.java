package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.mapper.PerformanceMapper;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.util.S3UploadUtils;
import com.ticketrush.shared.performance.event.PerformanceCreatedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
  @Transactional
  public PerformanceCreateResponse execute(
      PerformanceCreateRequest request,
      MultipartFile mainImage,
      MultipartFile model3d,
      List<MultipartFile> gallery) {

    validateFiles(mainImage, model3d, gallery);

    String mainImageUrl = s3UploadUtils.uploadFile(mainImage);
    String model3dUrl = s3UploadUtils.uploadFile(model3d);
    List<String> galleryUrls =
        (gallery != null) ? gallery.stream().map(s3UploadUtils::uploadFile).toList() : List.of();

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
  }
}

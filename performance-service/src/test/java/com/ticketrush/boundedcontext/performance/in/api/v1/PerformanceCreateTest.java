package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceCreateUseCase;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.util.FileKind;
import com.ticketrush.global.util.S3UploadUtils;
import com.ticketrush.shared.performance.event.PerformanceCreatedEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
@Transactional
class PerformanceCreateTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceCreateUseCase performanceCreateUseCase;

  @Autowired private PerformanceRepository performanceRepository;

  @Test
  @DisplayName("공연 등록 시 이미지 및 3D 모델 URL이 DB에 정상적으로 저장되어야 한다")
  void createPerformanceTest() {
    PerformanceCreateRequest request =
        PerformanceCreateRequest.builder()
            .title("콘서트")
            .performer("가수")
            .genre(Genre.MUSICAL)
            .description("설명")
            .showDate(LocalDate.now())
            .showTime(LocalTime.of(19, 0))
            .durationMinutes(120)
            .price(50000L)
            .totalSeats(100)
            .address("서울")
            .bookingOpenAt(LocalDateTime.of(2025, 8, 1, 20, 0))
            .build();

    MockMultipartFile mainImage =
        new MockMultipartFile("mainImage", "test.png", "image/png", "content".getBytes());
    MockMultipartFile model3d =
        new MockMultipartFile(
            "model3d", "test.glb", "application/octet-stream", "content".getBytes());
    List<MultipartFile> gallery =
        List.of(new MockMultipartFile("gallery", "g1.png", "image/png", "content".getBytes()));

    String expectedMainUrl = "https://s3.main-image.png";
    String expectedModelUrl = "https://s3.test-model.glb";
    String expectedGalleryUrl = "https://s3.gallery-1.png";

    given(s3UploadUtils.uploadFile(mainImage, FileKind.MAIN_IMAGE)).willReturn(expectedMainUrl);
    given(s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D)).willReturn(expectedModelUrl);
    given(s3UploadUtils.uploadFile(gallery.get(0), FileKind.GALLERY))
        .willReturn(expectedGalleryUrl);

    PerformanceCreateResponse response =
        performanceCreateUseCase.execute(request, mainImage, model3d, gallery);

    assertThat(response).isNotNull();

    var savedPerformance = performanceRepository.findById(response.performanceId()).orElseThrow();

    assertThat(savedPerformance.getTitle()).isEqualTo(request.title());
    assertThat(savedPerformance.getBookingOpenAt()).isEqualTo(request.bookingOpenAt());

    assertThat(savedPerformance.getImageMainUrl()).isEqualTo(expectedMainUrl);
    assertThat(savedPerformance.getImage3dUrl()).isEqualTo(expectedModelUrl);
    assertThat(savedPerformance.getImageGalleryUrls()).hasSize(1);
    assertThat(savedPerformance.getImageGalleryUrls().get(0)).isEqualTo(expectedGalleryUrl);

    ArgumentCaptor<PerformanceCreatedEvent> captor =
        ArgumentCaptor.forClass(PerformanceCreatedEvent.class);
    then(eventPublisher).should().publish(captor.capture());
    PerformanceCreatedEvent publishedEvent = captor.getValue();
    assertThat(publishedEvent.performanceId()).isEqualTo(savedPerformance.getId());
    assertThat(publishedEvent.title()).isEqualTo(request.title());
    assertThat(publishedEvent.totalSeats()).isEqualTo(request.totalSeats());
    assertThat(publishedEvent.showDate()).isEqualTo(request.showDate());
    assertThat(publishedEvent.showTime()).isEqualTo(request.showTime());
    assertThat(publishedEvent.price()).isEqualTo(request.price());
  }

  /*
   * 완료조건 4·5 — 파트별 확장자 분리(#636).
   *
   * #636 이전에는 ALLOWED_EXTENSIONS 한 벌(jpg, jpeg, png, glb, obj)을 모든 파트에 공용으로 적용해
   * 아래 두 케이스가 전부 통과했다.
   */
  @Test
  @DisplayName("model3d 파트에 이미지를 올리면 3D 모델 전용 코드로 거절한다")
  void rejectsImageOnModel3dPart() {
    MockMultipartFile mainImage =
        new MockMultipartFile("mainImage", "poster.png", "image/png", "content".getBytes());
    MockMultipartFile model3d =
        new MockMultipartFile("model3d", "character.png", "image/png", "content".getBytes());

    assertThatThrownBy(
            () -> performanceCreateUseCase.execute(validRequest(), mainImage, model3d, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.FILE_MODEL_3D_EXTENSION_NOT_ALLOWED);

    assertNothingUploaded();
  }

  @Test
  @DisplayName("mainImage 파트에 glb를 올리면 이미지 전용 코드로 거절한다")
  void rejectsModel3dOnMainImagePart() {
    MockMultipartFile mainImage =
        new MockMultipartFile("mainImage", "character.glb", "model/gltf-binary", "c".getBytes());
    MockMultipartFile model3d =
        new MockMultipartFile("model3d", "character.glb", "model/gltf-binary", "c".getBytes());

    assertThatThrownBy(
            () -> performanceCreateUseCase.execute(validRequest(), mainImage, model3d, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.FILE_IMAGE_EXTENSION_NOT_ALLOWED);

    assertNothingUploaded();
  }

  @Test
  @DisplayName("갤러리에 3D 모델을 섞어 올리면 거절한다")
  void rejectsModel3dInGallery() {
    MockMultipartFile mainImage =
        new MockMultipartFile("mainImage", "poster.png", "image/png", "content".getBytes());
    MockMultipartFile model3d =
        new MockMultipartFile("model3d", "character.glb", "model/gltf-binary", "c".getBytes());
    List<MultipartFile> gallery =
        List.of(new MockMultipartFile("gallery", "extra.obj", "model/obj", "c".getBytes()));

    assertThatThrownBy(
            () -> performanceCreateUseCase.execute(validRequest(), mainImage, model3d, gallery))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.FILE_IMAGE_EXTENSION_NOT_ALLOWED);

    assertNothingUploaded();
  }

  @Test
  @DisplayName("파트 상한을 넘는 파일은 413 코드로 거절한다")
  void rejectsOversizedFile() {
    byte[] oversized = new byte[(int) (FileKind.MAIN_IMAGE.getMaxSizeBytes() + 1)];

    MockMultipartFile mainImage =
        new MockMultipartFile("mainImage", "poster.png", "image/png", oversized);
    MockMultipartFile model3d =
        new MockMultipartFile("model3d", "character.glb", "model/gltf-binary", "c".getBytes());

    assertThatThrownBy(
            () -> performanceCreateUseCase.execute(validRequest(), mainImage, model3d, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.FILE_SIZE_EXCEEDED);

    assertNothingUploaded();
  }

  /**
   * 검증이 업로드보다 <b>앞</b>이라는 계약을 고정한다.
   *
   * <p>순서가 뒤집히면(파트마다 검사 직후 업로드) mainImage 를 올린 뒤 model3d 에서 거절되어, 거절될 요청마다 S3 에 정리해야 할 객체가 남는다. 결과
   * 코드만 검증하면 그 회귀를 잡지 못한다.
   */
  private void assertNothingUploaded() {
    then(s3UploadUtils).should(BDDMockito.never()).uploadFile(BDDMockito.any(), BDDMockito.any());
  }

  private PerformanceCreateRequest validRequest() {
    return PerformanceCreateRequest.builder()
        .title("콘서트")
        .performer("가수")
        .genre(Genre.MUSICAL)
        .description("설명")
        .showDate(LocalDate.now())
        .showTime(LocalTime.of(19, 0))
        .durationMinutes(120)
        .price(50000L)
        .totalSeats(100)
        .address("서울")
        .bookingOpenAt(LocalDateTime.of(2025, 8, 1, 20, 0))
        .build();
  }
}

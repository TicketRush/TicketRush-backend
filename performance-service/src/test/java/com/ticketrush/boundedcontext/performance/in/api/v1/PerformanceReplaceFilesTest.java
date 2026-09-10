package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceFileReplaceResponse;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceReplaceFilesUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.util.FileKind;
import com.ticketrush.global.util.S3UploadUtils;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 공연 파일 교체(#637)의 계약을 고정한다.
 *
 * <p>HTTP가 아니라 유스케이스를 직접 부르는 이유는 <b>크기 초과를 여기서만 검증할 수 있기 때문이다.</b> {@code MODEL_3D}의 상한 10MB가 서블릿
 * 전역 상한과 같은 값이라, HTTP 경로에서는 톰캣이 {@code MaxUploadSizeExceededException}으로 먼저 막아 {@code FileKind}의 크기
 * 검사가 도달하지 않는다. 서블릿을 거치지 않는 이 경로에서는 그대로 도달한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
@Transactional
class PerformanceReplaceFilesTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceReplaceFilesUseCase performanceReplaceFilesUseCase;
  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private EntityManager em;

  private static final String ORIGINAL_MAIN_URL = "https://s3.example/original-main.png";
  private static final String ORIGINAL_MODEL_URL = "https://s3.example/original-model.glb";
  private static final String ORIGINAL_GALLERY_URL_1 = "https://s3.example/original-g1.png";
  private static final String ORIGINAL_GALLERY_URL_2 = "https://s3.example/original-g2.png";

  private Long savePerformance() {
    Performance saved =
        performanceRepository.save(
            Performance.builder()
                .title("원래 공연명")
                .performer("원래 출연진")
                .genre(Genre.CONCERT)
                .description("원래 설명")
                .showDate(LocalDate.now().plusDays(30))
                .showTime(LocalTime.of(19, 0))
                .durationMinutes(120)
                .price(50000L)
                .totalSeats(100)
                .address("서울")
                .bookingOpenAt(LocalDateTime.of(2025, 8, 1, 20, 0))
                .imageMainUrl(ORIGINAL_MAIN_URL)
                .image3dUrl(ORIGINAL_MODEL_URL)
                .imageGalleryUrls(
                    new ArrayList<>(List.of(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2)))
                .build());

    // 1차 캐시에 남은 인스턴스를 그대로 읽어 "저장됐다"고 착각하지 않도록 비운다
    em.flush();
    em.clear();

    return saved.getId();
  }

  private Performance reload(Long id) {
    em.flush();
    em.clear();

    return performanceRepository.findById(id).orElseThrow();
  }

  private static MockMultipartFile file(String partName, String filename) {
    return new MockMultipartFile(partName, filename, null, "content".getBytes());
  }

  @Test
  @DisplayName("3D 모델만 보내면 그 URL만 바뀌고 나머지 파일은 그대로 남는다")
  void replaceModel3dOnly_keepsOtherUrls() {
    Long performanceId = savePerformance();
    MockMultipartFile model3d = file("model3d", "new-model.glb");
    String newModelUrl = "https://s3.example/new-model.glb";

    given(s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D)).willReturn(newModelUrl);

    PerformanceFileReplaceResponse response =
        performanceReplaceFilesUseCase.execute(performanceId, null, model3d, null);

    assertThat(response.image3dUrl()).isEqualTo(newModelUrl);
    assertThat(response.imageMainUrl()).isEqualTo(ORIGINAL_MAIN_URL);
    assertThat(response.imageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2);

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImage3dUrl()).isEqualTo(newModelUrl);
    assertThat(reloaded.getImageMainUrl()).isEqualTo(ORIGINAL_MAIN_URL);
    assertThat(reloaded.getImageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2);
  }

  @Test
  @DisplayName("메인 이미지만 보내면 그 URL만 바뀌고 나머지 파일은 그대로 남는다")
  void replaceMainImageOnly_keepsOtherUrls() {
    Long performanceId = savePerformance();
    MockMultipartFile mainImage = file("mainImage", "new-main.png");
    String newMainUrl = "https://s3.example/new-main.png";

    given(s3UploadUtils.uploadFile(mainImage, FileKind.MAIN_IMAGE)).willReturn(newMainUrl);

    performanceReplaceFilesUseCase.execute(performanceId, mainImage, null, null);

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImageMainUrl()).isEqualTo(newMainUrl);
    assertThat(reloaded.getImage3dUrl()).isEqualTo(ORIGINAL_MODEL_URL);
    assertThat(reloaded.getImageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2);
  }

  @Test
  @DisplayName("갤러리를 보내면 기존 갤러리 전체가 보낸 목록으로 치환된다")
  void replaceGallery_replacesWholeList() {
    Long performanceId = savePerformance();
    MockMultipartFile gallery = file("gallery", "new-g1.png");
    String newGalleryUrl = "https://s3.example/new-g1.png";

    given(s3UploadUtils.uploadFile(gallery, FileKind.GALLERY)).willReturn(newGalleryUrl);

    performanceReplaceFilesUseCase.execute(performanceId, null, null, List.of(gallery));

    Performance reloaded = reload(performanceId);

    // 기존 2장이 남지 않고 보낸 1장만 남는다 (부분 교체가 아니다)
    assertThat(reloaded.getImageGalleryUrls()).containsExactly(newGalleryUrl);
    assertThat(reloaded.getImageMainUrl()).isEqualTo(ORIGINAL_MAIN_URL);
    assertThat(reloaded.getImage3dUrl()).isEqualTo(ORIGINAL_MODEL_URL);
  }

  @Test
  @DisplayName("세 파트를 모두 보내면 전부 교체된다")
  void replaceAllParts_success() {
    MockMultipartFile mainImage = file("mainImage", "new-main.png");
    MockMultipartFile model3d = file("model3d", "new-model.glb");
    MockMultipartFile gallery = file("gallery", "new-g1.png");

    given(s3UploadUtils.uploadFile(mainImage, FileKind.MAIN_IMAGE))
        .willReturn("https://s3.e/m.png");
    given(s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D)).willReturn("https://s3.e/m.glb");
    given(s3UploadUtils.uploadFile(gallery, FileKind.GALLERY)).willReturn("https://s3.e/g.png");

    Long performanceId = savePerformance();

    performanceReplaceFilesUseCase.execute(performanceId, mainImage, model3d, List.of(gallery));

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImageMainUrl()).isEqualTo("https://s3.e/m.png");
    assertThat(reloaded.getImage3dUrl()).isEqualTo("https://s3.e/m.glb");
    assertThat(reloaded.getImageGalleryUrls()).containsExactly("https://s3.e/g.png");
  }

  @Test
  @DisplayName("확장자가 파트와 맞지 않으면 400으로 거절하고 업로드를 시작하지 않는다")
  void invalidExtension_rejectedBeforeAnyUpload() {
    Long performanceId = savePerformance();
    MockMultipartFile model3d = file("model3d", "not-a-model.png");

    assertThatThrownBy(
            () -> performanceReplaceFilesUseCase.execute(performanceId, null, model3d, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.FILE_MODEL_3D_EXTENSION_NOT_ALLOWED);

    // 검증이 업로드보다 앞선다는 순서 계약. 뒤집히면 거절될 요청마다 S3에 고아 객체가 생긴다
    then(s3UploadUtils).shouldHaveNoInteractions();

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImage3dUrl()).isEqualTo(ORIGINAL_MODEL_URL);
  }

  @Test
  @DisplayName("파트 상한을 넘는 크기면 413으로 거절하고 기존 URL이 그대로 남는다")
  void sizeExceeded_rejectedAndUrlsKept() {
    Long performanceId = savePerformance();
    byte[] tooLarge = new byte[(int) FileKind.MODEL_3D.getMaxSizeBytes() + 1];
    MockMultipartFile model3d = new MockMultipartFile("model3d", "huge.glb", null, tooLarge);

    assertThatThrownBy(
            () -> performanceReplaceFilesUseCase.execute(performanceId, null, model3d, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.FILE_SIZE_EXCEEDED);

    then(s3UploadUtils).shouldHaveNoInteractions();

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImage3dUrl()).isEqualTo(ORIGINAL_MODEL_URL);
  }

  @Test
  @DisplayName("갤러리를 상한보다 많이 보내면 400으로 거절한다")
  void galleryOverLimit_rejected() {
    Long performanceId = savePerformance();
    List<MultipartFile> gallery = new ArrayList<>();

    for (int i = 0; i <= FileKind.GALLERY_MAX_COUNT; i++) {
      gallery.add(file("gallery", "g" + i + ".png"));
    }

    assertThatThrownBy(
            () -> performanceReplaceFilesUseCase.execute(performanceId, null, null, gallery))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_GALLERY_LIMIT_EXCEEDED);

    then(s3UploadUtils).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("파트를 하나도 보내지 않으면 400으로 거절한다")
  void noPart_rejected() {
    Long performanceId = savePerformance();

    assertThatThrownBy(
            () -> performanceReplaceFilesUseCase.execute(performanceId, null, null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_NO_FILE_TO_REPLACE);
  }

  @Test
  @DisplayName("빈 갤러리 목록만 보내면 파트를 보내지 않은 것과 같이 400으로 거절한다")
  void emptyGalleryList_rejected() {
    Long performanceId = savePerformance();

    assertThatThrownBy(
            () -> performanceReplaceFilesUseCase.execute(performanceId, null, null, List.of()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_NO_FILE_TO_REPLACE);
  }

  @Test
  @DisplayName("빈 파트는 보내지 않은 것으로 보고, 함께 온 실제 파일만 교체한다")
  void emptyPart_treatedAsAbsent() {
    // 브라우저는 파일을 고르지 않은 <input type="file">도 filename=""인 0바이트 파트로 실어 보낸다.
    // 이걸 FILE_EMPTY로 거절하면 "입력 세 개 중 하나만 골랐다"는 가장 흔한 폼이 전건 400이 된다
    MockMultipartFile emptyMain = new MockMultipartFile("mainImage", "", null, new byte[0]);
    MockMultipartFile model3d = file("model3d", "new-model.glb");
    String newModelUrl = "https://s3.example/new-model.glb";

    given(s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D)).willReturn(newModelUrl);

    Long performanceId = savePerformance();

    performanceReplaceFilesUseCase.execute(performanceId, emptyMain, model3d, null);

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImage3dUrl()).isEqualTo(newModelUrl);
    assertThat(reloaded.getImageMainUrl()).isEqualTo(ORIGINAL_MAIN_URL);
  }

  @Test
  @DisplayName("빈 파트만 보내면 아무것도 보내지 않은 것과 같이 400으로 거절한다")
  void onlyEmptyParts_rejected() {
    Long performanceId = savePerformance();
    MockMultipartFile emptyMain = new MockMultipartFile("mainImage", "", null, new byte[0]);

    assertThatThrownBy(
            () -> performanceReplaceFilesUseCase.execute(performanceId, emptyMain, null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_NO_FILE_TO_REPLACE);

    then(s3UploadUtils).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("빈 갤러리 목록을 다른 파트와 함께 보내도 기존 갤러리는 지워지지 않는다")
  void emptyGalleryWithOtherPart_keepsGallery() {
    // 갤러리 비우기는 계약에 없다. 빈 목록이 "치환하라"로 읽히면 갤러리가 통째로 사라진다
    MockMultipartFile model3d = file("model3d", "new-model.glb");

    given(s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D))
        .willReturn("https://s3.example/new-model.glb");

    Long performanceId = savePerformance();

    performanceReplaceFilesUseCase.execute(performanceId, null, model3d, List.of());

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2);
  }

  @Test
  @DisplayName("도메인에 빈 갤러리 목록을 직접 넘겨도 기존 갤러리는 지워지지 않는다")
  void replaceFilesWithEmptyGallery_keepsGallery() {
    // 유스케이스는 빈 목록을 null로 바꿔 넘기므로 이 분기에 도달하지 않는다. 그래서 도메인 가드를
    // 지워도 유스케이스 테스트는 전부 통과한다 — 호출부 조건 한 줄에 갤러리 전삭제가 걸리지 않도록
    // 도메인 쪽에서 직접 고정한다
    Long performanceId = savePerformance();
    Performance performance = performanceRepository.findById(performanceId).orElseThrow();

    performance.replaceFiles(null, null, List.of());

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2);
  }

  @Test
  @DisplayName("존재하지 않는 공연에 요청하면 404를 반환한다")
  void performanceNotFound() {
    MockMultipartFile model3d = file("model3d", "new-model.glb");

    assertThatThrownBy(() -> performanceReplaceFilesUseCase.execute(999_999L, null, model3d, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_NOT_FOUND);

    // 없는 공연에 대해서는 페이로드를 따지기 전에 끝낸다
    then(s3UploadUtils).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("삭제된 공연에 요청하면 404를 반환한다")
  void deletedPerformance_notFound() {
    Long performanceId = savePerformance();
    Performance performance = performanceRepository.findById(performanceId).orElseThrow();

    performance.softDelete();

    em.flush();
    em.clear();

    MockMultipartFile model3d = file("model3d", "new-model.glb");

    // @SQLRestriction이 로딩 경로에 자동 적용돼 삭제된 행은 findById에서 아예 나오지 않는다.
    // 자동이라 조용히 깨질 수 있어 테스트로 고정한다
    assertThatThrownBy(
            () -> performanceReplaceFilesUseCase.execute(performanceId, null, model3d, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_NOT_FOUND);
  }
}

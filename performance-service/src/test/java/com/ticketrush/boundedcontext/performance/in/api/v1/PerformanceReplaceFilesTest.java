package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceFileReplaceRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceFileReplaceResponse;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceGetDetailUseCase;
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
 * 공연 파일 교체(#637)와 변경 지시 request 파트(#688)의 계약을 고정한다.
 *
 * <p>#637 시절의 케이스는 전부 {@code request=null}로 그대로 두어, request 파트를 보내지 않는 클라이언트(배포 전 프론트)가 이전과 똑같이
 * 동작한다는 하위 호환을 지킨다. #688 케이스는 클래스 뒤쪽에 모아 둔다.
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
  @Autowired private PerformanceGetDetailUseCase performanceGetDetailUseCase;
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
        performanceReplaceFilesUseCase.execute(performanceId, null, model3d, null, null);

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

    performanceReplaceFilesUseCase.execute(performanceId, mainImage, null, null, null);

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

    performanceReplaceFilesUseCase.execute(performanceId, null, null, List.of(gallery), null);

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

    performanceReplaceFilesUseCase.execute(
        performanceId, mainImage, model3d, List.of(gallery), null);

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
            () -> performanceReplaceFilesUseCase.execute(performanceId, null, model3d, null, null))
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
            () -> performanceReplaceFilesUseCase.execute(performanceId, null, model3d, null, null))
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
            () -> performanceReplaceFilesUseCase.execute(performanceId, null, null, gallery, null))
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
            () -> performanceReplaceFilesUseCase.execute(performanceId, null, null, null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_NO_FILE_TO_REPLACE);
  }

  @Test
  @DisplayName("빈 갤러리 목록만 보내면 파트를 보내지 않은 것과 같이 400으로 거절한다")
  void emptyGalleryList_rejected() {
    Long performanceId = savePerformance();

    assertThatThrownBy(
            () ->
                performanceReplaceFilesUseCase.execute(performanceId, null, null, List.of(), null))
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

    performanceReplaceFilesUseCase.execute(performanceId, emptyMain, model3d, null, null);

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
            () ->
                performanceReplaceFilesUseCase.execute(performanceId, emptyMain, null, null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_NO_FILE_TO_REPLACE);

    then(s3UploadUtils).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("request 파트 없이 빈 갤러리 목록을 다른 파트와 함께 보내도 기존 갤러리는 지워지지 않는다")
  void emptyGalleryWithOtherPart_keepsGallery() {
    // request 파트가 없는 경로는 #637 계약 그대로다(보내면 전체 치환, 안 보내면 유지). #688에서 도메인의
    // 빈 목록 가드가 걷혔으므로, 유스케이스가 빈 목록을 null로 바꿔 넘기는 것이 갤러리 전삭제를 막는 유일한
    // 방어다 — 이 테스트를 지우면 그 방어가 조용히 사라질 수 있다
    MockMultipartFile model3d = file("model3d", "new-model.glb");

    given(s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D))
        .willReturn("https://s3.example/new-model.glb");

    Long performanceId = savePerformance();

    performanceReplaceFilesUseCase.execute(performanceId, null, model3d, List.of(), null);

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2);
  }

  @Test
  @DisplayName("도메인에 빈 갤러리 목록을 직접 넘기면 기존 갤러리가 비워진다 (#688부터 빈 목록은 비우기다)")
  void replaceFilesWithEmptyGallery_clearsGallery() {
    // #637에서는 빈 목록을 무시하는 도메인 가드가 있었고 이 테스트가 그것을 고정했다. #688이 유지 목록으로
    // 비우기를 지원하면서 가드를 걷어냈으므로 의미를 반전해 고정한다. 이제 "request 없는 빈 갤러리 파트 =
    // 유지"를 지키는 것은 유스케이스의 null 변환뿐이며 emptyGalleryWithOtherPart_keepsGallery 가 그것을 지킨다.
    // reload 로 DB 를 다시 읽으므로 @OrderColumn 컬렉션의 clear 가 빈 상태로 flush 되는지도 함께 확인된다.
    Long performanceId = savePerformance();
    Performance performance = performanceRepository.findById(performanceId).orElseThrow();

    performance.replaceFiles(null, null, List.of());

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImageGalleryUrls()).isEmpty();
  }

  @Test
  @DisplayName("존재하지 않는 공연에 요청하면 404를 반환한다")
  void performanceNotFound() {
    MockMultipartFile model3d = file("model3d", "new-model.glb");

    assertThatThrownBy(
            () -> performanceReplaceFilesUseCase.execute(999_999L, null, model3d, null, null))
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

    performance.softDelete(LocalDateTime.now());

    em.flush();
    em.clear();

    MockMultipartFile model3d = file("model3d", "new-model.glb");

    // @SQLRestriction이 로딩 경로에 자동 적용돼 삭제된 행은 findById에서 아예 나오지 않는다.
    // 자동이라 조용히 깨질 수 있어 테스트로 고정한다
    assertThatThrownBy(
            () -> performanceReplaceFilesUseCase.execute(performanceId, null, model3d, null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_NOT_FOUND);
  }

  // ---------- #688 request 파트: 갤러리 유지 목록 · 3D 모델 비우기 ----------

  private static PerformanceFileReplaceRequest keep(String... urls) {
    return new PerformanceFileReplaceRequest(List.of(urls), null);
  }

  private static PerformanceFileReplaceRequest clearModel3d() {
    return new PerformanceFileReplaceRequest(null, true);
  }

  private void assertRejected(Long performanceId, List<String> keepUrls, ErrorStatus expected) {
    assertThatThrownBy(
            () ->
                performanceReplaceFilesUseCase.execute(
                    performanceId,
                    null,
                    null,
                    null,
                    new PerformanceFileReplaceRequest(keepUrls, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("유지 목록 일부 + 신규 파일이면 최종 갤러리는 유지 목록 순서 뒤에 신규 순서로 이어진다")
  void keepSomeAndAddNew_orderIsKeepThenNew() {
    Long performanceId = savePerformance();
    MockMultipartFile newFile = file("gallery", "new-g.png");
    String newUrl = "https://s3.example/new-g.png";

    given(s3UploadUtils.uploadFile(newFile, FileKind.GALLERY)).willReturn(newUrl);

    PerformanceFileReplaceResponse response =
        performanceReplaceFilesUseCase.execute(
            performanceId, null, null, List.of(newFile), keep(ORIGINAL_GALLERY_URL_2));

    // 유지 목록에 없는 g1 은 빠지고, 유지한 g2 뒤에 신규가 붙는다
    assertThat(response.imageGalleryUrls()).containsExactly(ORIGINAL_GALLERY_URL_2, newUrl);
    assertThat(reload(performanceId).getImageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_2, newUrl);
  }

  @Test
  @DisplayName("유지 목록의 순서를 바꿔 보내면 그 순서로 저장된다 (재배열은 계약이다)")
  void keepReordered_reordersGallery() {
    Long performanceId = savePerformance();

    performanceReplaceFilesUseCase.execute(
        performanceId, null, null, null, keep(ORIGINAL_GALLERY_URL_2, ORIGINAL_GALLERY_URL_1));

    assertThat(reload(performanceId).getImageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_2, ORIGINAL_GALLERY_URL_1);
    // 유지 목록만으로는 올릴 파일이 없다 — request 파트만으로 성공하는 경로
    then(s3UploadUtils).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("빈 유지 목록에 신규 파일이 없으면 갤러리가 비워진다")
  void emptyKeepWithoutNewFiles_clearsGallery() {
    Long performanceId = savePerformance();

    PerformanceFileReplaceResponse response =
        performanceReplaceFilesUseCase.execute(performanceId, null, null, null, keep());

    assertThat(response.imageGalleryUrls()).isEmpty();
    assertThat(reload(performanceId).getImageGalleryUrls()).isEmpty();
    then(s3UploadUtils).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("clear_model3d 가 true 면 3D 모델 URL 이 비워져 응답·DB·상세 조회 모두 null 이다")
  void clearModel3d_clearsUrlEverywhere() {
    Long performanceId = savePerformance();

    PerformanceFileReplaceResponse response =
        performanceReplaceFilesUseCase.execute(performanceId, null, null, null, clearModel3d());

    assertThat(response.image3dUrl()).isNull();
    assertThat(response.imageMainUrl()).isEqualTo(ORIGINAL_MAIN_URL);
    assertThat(response.imageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2);

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImage3dUrl()).isNull();
    // 상세 조회는 전역 NON_NULL 로 키가 빠진다. 값이 null 인 것까지 여기서 고정한다
    assertThat(performanceGetDetailUseCase.execute(performanceId).image3dUrl()).isNull();
    then(s3UploadUtils).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("유지 목록 없이 request 만 보내고 갤러리 파일을 함께 보내면 #637 그대로 전체 치환된다")
  void requestWithoutKeep_galleryFileStillReplacesWhole() {
    Long performanceId = savePerformance();
    MockMultipartFile newFile = file("gallery", "new-g.png");
    String newUrl = "https://s3.example/new-g.png";

    given(s3UploadUtils.uploadFile(newFile, FileKind.GALLERY)).willReturn(newUrl);

    // keep_gallery_urls 키를 생략(null)한 request — 갤러리 규칙은 기존 계약 그대로여야 한다
    performanceReplaceFilesUseCase.execute(
        performanceId, null, null, List.of(newFile), clearModel3d());

    Performance reloaded = reload(performanceId);

    assertThat(reloaded.getImageGalleryUrls()).containsExactly(newUrl);
    assertThat(reloaded.getImage3dUrl()).isNull();
  }

  @Test
  @DisplayName("request 가 {} 이거나 clear_model3d:false 뿐이면 변경 지시가 없어 400 이다")
  void requestWithoutInstruction_rejected() {
    Long performanceId = savePerformance();

    for (PerformanceFileReplaceRequest noInstruction :
        List.of(
            new PerformanceFileReplaceRequest(null, null),
            new PerformanceFileReplaceRequest(null, false))) {
      assertThatThrownBy(
              () ->
                  performanceReplaceFilesUseCase.execute(
                      performanceId, null, null, null, noInstruction))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getErrorStatus())
          .isEqualTo(ErrorStatus.PERFORMANCE_NO_FILE_TO_REPLACE);
    }

    then(s3UploadUtils).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("현재 갤러리에 없는 URL 을 유지하려 하면 400 이고 기존 갤러리는 그대로 남는다 (stale 화면 감지)")
  void staleKeepUrl_rejectedAndGalleryKept() {
    Long performanceId = savePerformance();
    MockMultipartFile newFile = file("gallery", "new-g.png");

    assertThatThrownBy(
            () ->
                performanceReplaceFilesUseCase.execute(
                    performanceId,
                    null,
                    null,
                    List.of(newFile),
                    keep(ORIGINAL_GALLERY_URL_1, "https://s3.example/already-gone.png")))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_KEEP_GALLERY_URL_NOT_FOUND);

    // 검증이 업로드보다 앞선다 — 함께 온 신규 파일도 올라가지 않는다
    then(s3UploadUtils).shouldHaveNoInteractions();
    assertThat(reload(performanceId).getImageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2);
  }

  @Test
  @DisplayName("유지 목록에 같은 URL 이 중복되면 400 이다")
  void duplicatedKeepUrl_rejected() {
    Long performanceId = savePerformance();

    assertThatThrownBy(
            () ->
                performanceReplaceFilesUseCase.execute(
                    performanceId,
                    null,
                    null,
                    null,
                    keep(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_1)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_KEEP_GALLERY_URL_DUPLICATED);

    assertThat(reload(performanceId).getImageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2);
  }

  @Test
  @DisplayName("유지 + 신규 합이 상한을 넘으면 기존 갤러리 상한 코드로 400 이다")
  void keepPlusNewOverLimit_rejected() {
    Long performanceId = savePerformance();
    List<MultipartFile> newFiles = List.of(file("gallery", "n1.png"), file("gallery", "n2.png"));

    // 유지 2 + 신규 2 = 4 > 3. 신규 파일만 세면 2장이라 통과해 버리는 경계
    assertThatThrownBy(
            () ->
                performanceReplaceFilesUseCase.execute(
                    performanceId,
                    null,
                    null,
                    newFiles,
                    keep(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_GALLERY_LIMIT_EXCEEDED);

    then(s3UploadUtils).shouldHaveNoInteractions();
    assertThat(reload(performanceId).getImageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2);
  }

  @Test
  @DisplayName("유지 목록의 null·빈 문자열 원소는 NPE 없이 400 으로 거절된다")
  void nullOrBlankKeepElement_rejectedWithoutNpe() {
    Long performanceId = savePerformance();

    // 존재 확인이 null 허용 컬렉션에 기대고 있다. Set.of·List.copyOf 로 바꾸는 리팩토링이 500 을 만들지 않도록 고정한다
    List<String> singleNull = new ArrayList<>();
    singleNull.add(null);
    assertRejected(performanceId, singleNull, ErrorStatus.PERFORMANCE_KEEP_GALLERY_URL_NOT_FOUND);

    List<String> doubleNull = new ArrayList<>();
    doubleNull.add(null);
    doubleNull.add(null);
    assertRejected(performanceId, doubleNull, ErrorStatus.PERFORMANCE_KEEP_GALLERY_URL_DUPLICATED);

    assertRejected(performanceId, List.of(""), ErrorStatus.PERFORMANCE_KEEP_GALLERY_URL_NOT_FOUND);

    then(s3UploadUtils).shouldHaveNoInteractions();
    assertThat(reload(performanceId).getImageGalleryUrls())
        .containsExactly(ORIGINAL_GALLERY_URL_1, ORIGINAL_GALLERY_URL_2);
  }

  @Test
  @DisplayName("유지 목록을 보내지 않으면 상한은 #637 그대로 신규 파일 수만 센다")
  void withoutKeep_limitCountsOnlyNewFiles() {
    Long performanceId = savePerformance();
    List<MultipartFile> newFiles =
        List.of(file("gallery", "n1.png"), file("gallery", "n2.png"), file("gallery", "n3.png"));

    given(s3UploadUtils.uploadFile(any(), eq(FileKind.GALLERY)))
        .willReturn("https://s3.example/n.png");

    // 기존 2장이 있어도 유지 목록이 없으면 전체 치환이라 신규 3장은 상한 안이다
    performanceReplaceFilesUseCase.execute(performanceId, null, null, newFiles, clearModel3d());

    assertThat(reload(performanceId).getImageGalleryUrls()).hasSize(3);
  }

  @Test
  @DisplayName("clear_model3d 와 새 model3d 파일을 함께 보내면 모순이라 400 이고 기존 모델은 그대로 남는다")
  void clearModel3dWithNewModel_rejected() {
    Long performanceId = savePerformance();
    MockMultipartFile model3d = file("model3d", "new-model.glb");

    assertThatThrownBy(
            () ->
                performanceReplaceFilesUseCase.execute(
                    performanceId, null, model3d, null, clearModel3d()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_MODEL3D_CLEAR_CONFLICT);

    then(s3UploadUtils).shouldHaveNoInteractions();
    assertThat(reload(performanceId).getImage3dUrl()).isEqualTo(ORIGINAL_MODEL_URL);
  }
}

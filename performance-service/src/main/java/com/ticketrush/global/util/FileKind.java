package com.ticketrush.global.util;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.springframework.web.multipart.MultipartFile;

/**
 * 공연 등록 멀티파트 파트별 업로드 정책의 단일 출처다.
 *
 * <p>#636 이전에는 {@code S3UploadUtils}가 {@code jpg, jpeg, png, glb, obj} 화이트리스트 한 벌을 모든 파트에 공용으로
 * 적용했다. 그래서 {@code model3d} 파트에 png를, {@code mainImage} 파트에 glb를 올려도 통과했다. 파트마다 받아야 하는 형식이 다르므로 정책을
 * 상수별로 쪼갠다.
 *
 * <p>등록(#636)과 교체(#637)가 이 정책을 공유한다. 파트가 늘면 여기에 상수를 추가하는 것으로 끝나야 하며, 검증 규칙을 호출부에 복사하지 않는다.
 *
 * <p><b>다만 "비어 있는 파트"의 취급은 두 경로가 갈린다.</b> 교체는 빈 파트를 보내지 않은 것으로 보고 넘기지만(#637 — 보낸 파트만 교체한다는 계약을 지키려면
 * 그래야 한다), 등록은 여기 {@link #validate}가 {@code FILE_EMPTY}로 거절한다. 등록은 세 파트가 모두 필수라 빈 파트를 넘길 여지가 없어서
 * 그대로 뒀다. 등록의 갤러리도 같은 관대함이 필요해지면 그때 두 경로를 다시 맞춘다.
 *
 * <p><b>MIME type은 검증하지 않는다.</b> 판정은 파일명 확장자로만 한다 — 브라우저가 보내는 {@code Content-Type}은 클라이언트가 자유롭게 조작할
 * 수 있어 신뢰할 수 없다. 여기서 만드는 contentType은 검증용이 아니라, S3에 저장된 객체를 브라우저가 직접 GET 할 때 올바른 타입으로 내려주기 위한
 * 메타데이터다.
 */
@Getter
public enum FileKind {
  MAIN_IMAGE(
      List.of("jpg", "jpeg", "png"),
      5L * 1024 * 1024,
      "performances/main",
      ErrorStatus.FILE_IMAGE_EXTENSION_NOT_ALLOWED),

  GALLERY(
      List.of("jpg", "jpeg", "png"),
      5L * 1024 * 1024,
      "performances/gallery",
      ErrorStatus.FILE_IMAGE_EXTENSION_NOT_ALLOWED),

  /*
   * 상한 10MB는 서블릿 전역 상한(spring.servlet.multipart.max-file-size)과 같은 값이다. 현재 실제로 통과 가능한
   * 최대치를 그대로 명시했다(#636 grilling 결정).
   *
   * #637 시점에 프론트 실측을 받았다 — 개별 GLB가 0.18~0.41MB, 캐릭터 구성 6개를 합쳐 약 1.84MB다. 다만 공연에
   * 등록되는 것은 body·hair·outfit에 리깅/애니메이션까지 합친 단일 GLB라 그 용량은 아직 모른다. 현재 상한으로
   * 충분해 보이지만 확정된 값이 아니므로, 통합 GLB 실측치가 오면 아래 두 문단의 관계와 함께 다시 본다.
   *
   * 전역 상한과 같은 값이므로 <b>HTTP 요청 경로에서는 이 파트의 크기 검사가 도달하지 않는다</b> — 10MB를 넘는 파트는 톰캣이 먼저
   * MaxUploadSizeExceededException으로 막는다. 두 경로 모두 413 FILE_413_001로 나가 응답은 같다. 서블릿을 거치지 않는
   * 직접 호출(테스트, #637의 내부 호출)에서는 그대로 도달한다. GLB 실측치를 받아 값을 조정할 때 이 관계를 함께 봐야 한다.
   *
   * 이 값을 올리려면 전역 상한만으로는 부족하다 — prod nginx의 client_max_body_size(지시어가 없으면 기본 1MB)와
   * 게이트웨이 경유 경로를 함께 확인해야 요청이 앱까지 도달한다.
   */
  MODEL_3D(
      List.of("glb", "obj"),
      10L * 1024 * 1024,
      "performances/3d",
      ErrorStatus.FILE_MODEL_3D_EXTENSION_NOT_ALLOWED);

  /**
   * 갤러리 파트로 받을 수 있는 최대 개수.
   *
   * <p>등록({@code PerformanceCreateUseCase})과 교체({@code PerformanceReplaceFilesUseCase}) 두 곳이 같은 제한을
   * 걸어야 해서 여기에 둔다. 한쪽만 고치면 등록과 교체의 상한이 갈린다.
   *
   * <p><b>이 상수가 덮는 범위는 검사 로직까지다.</b> 사용자에게 보이는 문구는 여전히 "3"을 직접 적고 있어, 값을 바꾸면 네 곳을 함께 고쳐야 한다 —
   * {@code PERFORMANCE_GALLERY_LIMIT_EXCEEDED}의 메시지, {@code PerformanceAdminController}의 등록·교체 두
   * {@code @Operation} 설명, 그리고 {@code PerformanceCreateSwaggerBody}의 파트 설명이다. 교체 쪽 Swagger 바디만 이
   * 상수를 {@code maxItems}로 참조한다.
   *
   * <p>enum 인스턴스 필드로 두지 않은 이유: 개수 제한은 갤러리에만 있는 값이라 {@code MAIN_IMAGE}·{@code MODEL_3D}에는 의미 없는 1을
   * 붙여야 하는데, 그 1이 "1개까지 리스트로 받을 수 있다"는 없는 계약처럼 읽힌다.
   */
  public static final int GALLERY_MAX_COUNT = 3;

  private final List<String> allowedExtensions;
  private final long maxSizeBytes;
  private final String keyPrefix;
  private final ErrorStatus extensionErrorStatus;

  FileKind(
      List<String> allowedExtensions,
      long maxSizeBytes,
      String keyPrefix,
      ErrorStatus extensionErrorStatus) {
    this.allowedExtensions = allowedExtensions;
    this.maxSizeBytes = maxSizeBytes;
    this.keyPrefix = keyPrefix;
    this.extensionErrorStatus = extensionErrorStatus;
  }

  private static final Map<String, String> CONTENT_TYPES =
      Map.of(
          "jpg", "image/jpeg",
          "jpeg", "image/jpeg",
          "png", "image/png",
          "glb", "model/gltf-binary",
          "obj", "model/obj");

  private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

  /**
   * 파일이 이 파트의 정책을 만족하는지 검사한다. 위반이면 {@link BusinessException}을 던진다.
   *
   * <p>호출 순서가 계약이다 — 업로드를 시작하기 <b>전에</b> 모든 파트를 검사해야 한다. 업로드 도중에 검사하면 앞선 파트가 이미 S3에 올라간 뒤 뒤 파트에서
   * 거절되어, 매 요청마다 정리해야 할 객체가 생긴다.
   */
  public void validate(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException(ErrorStatus.FILE_EMPTY);
    }

    validateExtension(file);

    if (file.getSize() > maxSizeBytes) {
      throw new BusinessException(ErrorStatus.FILE_SIZE_EXCEEDED);
    }
  }

  /** 검증을 통과한 파일에 대해 충돌하지 않는 S3 객체 키를 만든다. */
  public String newObjectKey(MultipartFile file) {
    return keyPrefix + "/" + UUID.randomUUID() + "." + extensionOf(file);
  }

  /** 객체 키의 확장자에 대응하는 Content-Type. 모르는 확장자면 {@code application/octet-stream}. */
  public static String contentTypeOf(String objectKey) {
    int dot = objectKey.lastIndexOf('.');

    if (dot < 0) {
      return DEFAULT_CONTENT_TYPE;
    }

    String extension = objectKey.substring(dot + 1).toLowerCase(Locale.ROOT);

    return CONTENT_TYPES.getOrDefault(extension, DEFAULT_CONTENT_TYPE);
  }

  private void validateExtension(MultipartFile file) {
    if (!allowedExtensions.contains(extensionOf(file))) {
      throw new BusinessException(extensionErrorStatus);
    }
  }

  private String extensionOf(MultipartFile file) {
    String filename = file.getOriginalFilename();

    if (filename == null || !filename.contains(".")) {
      throw new BusinessException(ErrorStatus.FILE_INVALID_EXTENSION);
    }

    return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
  }
}

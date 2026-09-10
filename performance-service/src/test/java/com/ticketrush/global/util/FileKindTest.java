package com.ticketrush.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 파트별 업로드 정책을 고정한다(#636).
 *
 * <p>#636 이전에는 {@code jpg, jpeg, png, glb, obj} 화이트리스트 한 벌을 모든 파트가 공용으로 썼다. 그래서 3D 모델 자리에 png를, 메인
 * 이미지 자리에 glb를 올려도 통과했다.
 */
class FileKindTest {

  @Nested
  @DisplayName("확장자 검증")
  class ExtensionValidation {

    @Test
    @DisplayName("3D 모델 파트는 glb·obj만 받는다")
    void model3dAcceptsOnlyModelExtensions() {
      assertThatCode(() -> FileKind.MODEL_3D.validate(file("character.glb")))
          .doesNotThrowAnyException();
      assertThatCode(() -> FileKind.MODEL_3D.validate(file("character.obj")))
          .doesNotThrowAnyException();

      assertThatThrownBy(() -> FileKind.MODEL_3D.validate(file("character.png")))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getErrorStatus())
          .isEqualTo(ErrorStatus.FILE_MODEL_3D_EXTENSION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("이미지 파트는 jpg·jpeg·png만 받는다")
    void imagePartsAcceptOnlyImageExtensions() {
      assertThatCode(() -> FileKind.MAIN_IMAGE.validate(file("poster.jpg")))
          .doesNotThrowAnyException();
      assertThatCode(() -> FileKind.GALLERY.validate(file("shot.jpeg"))).doesNotThrowAnyException();

      assertThatThrownBy(() -> FileKind.MAIN_IMAGE.validate(file("character.glb")))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getErrorStatus())
          .isEqualTo(ErrorStatus.FILE_IMAGE_EXTENSION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("확장자 대소문자는 구분하지 않는다")
    void extensionIsCaseInsensitive() {
      assertThatCode(() -> FileKind.MAIN_IMAGE.validate(file("POSTER.PNG")))
          .doesNotThrowAnyException();
      assertThatCode(() -> FileKind.MODEL_3D.validate(file("Character.GLB")))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("확장자가 없으면 거절한다")
    void rejectsFilenameWithoutExtension() {
      assertThatThrownBy(() -> FileKind.MAIN_IMAGE.validate(file("noextension")))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getErrorStatus())
          .isEqualTo(ErrorStatus.FILE_INVALID_EXTENSION);
    }

    @Test
    @DisplayName("빈 파일은 거절한다")
    void rejectsEmptyFile() {
      MultipartFile empty = new MockMultipartFile("part", "poster.png", "image/png", new byte[0]);

      assertThatThrownBy(() -> FileKind.MAIN_IMAGE.validate(empty))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getErrorStatus())
          .isEqualTo(ErrorStatus.FILE_EMPTY);
    }
  }

  @Nested
  @DisplayName("크기 검증")
  class SizeValidation {

    @Test
    @DisplayName("파트 상한을 넘으면 413 코드로 거절한다")
    void rejectsOversizedFile() {
      byte[] oversized = new byte[(int) (FileKind.MAIN_IMAGE.getMaxSizeBytes() + 1)];
      MultipartFile file = new MockMultipartFile("part", "poster.png", "image/png", oversized);

      assertThatThrownBy(() -> FileKind.MAIN_IMAGE.validate(file))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getErrorStatus())
          .isEqualTo(ErrorStatus.FILE_SIZE_EXCEEDED);
    }

    @Test
    @DisplayName("3D 모델은 이미지보다 큰 상한을 갖는다")
    void model3dHasLargerLimitThanImages() {
      assertThat(FileKind.MODEL_3D.getMaxSizeBytes())
          .isGreaterThan(FileKind.MAIN_IMAGE.getMaxSizeBytes());
    }

    @Test
    @DisplayName("상한과 정확히 같은 크기는 통과한다")
    void acceptsFileAtExactLimit() {
      byte[] exact = new byte[(int) FileKind.MAIN_IMAGE.getMaxSizeBytes()];
      MultipartFile file = new MockMultipartFile("part", "poster.png", "image/png", exact);

      assertThatCode(() -> FileKind.MAIN_IMAGE.validate(file)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("객체 키와 Content-Type")
  class ObjectKeyAndContentType {

    @Test
    @DisplayName("키는 파트별 prefix 아래에 만들어지고 확장자를 유지한다")
    void keyUsesPartPrefixAndKeepsExtension() {
      String key = FileKind.MODEL_3D.newObjectKey(file("character.glb"));

      assertThat(key).startsWith("performances/3d/").endsWith(".glb");
      assertThat(FileKind.MAIN_IMAGE.newObjectKey(file("poster.png")))
          .startsWith("performances/main/");
      assertThat(FileKind.GALLERY.newObjectKey(file("shot.png")))
          .startsWith("performances/gallery/");
    }

    @Test
    @DisplayName("같은 파일명을 두 번 올려도 키가 겹치지 않는다")
    void keyIsUniquePerUpload() {
      assertThat(FileKind.MAIN_IMAGE.newObjectKey(file("poster.png")))
          .isNotEqualTo(FileKind.MAIN_IMAGE.newObjectKey(file("poster.png")));
    }

    @Test
    @DisplayName("확장자에 맞는 Content-Type을 붙인다 — 브라우저가 직접 GET 하기 때문이다")
    void mapsContentTypeByExtension() {
      assertThat(FileKind.contentTypeOf("performances/3d/x.glb")).isEqualTo("model/gltf-binary");
      assertThat(FileKind.contentTypeOf("performances/main/x.png")).isEqualTo("image/png");
      assertThat(FileKind.contentTypeOf("performances/main/x.jpg")).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("모르는 확장자는 octet-stream으로 둔다")
    void fallsBackToOctetStream() {
      assertThat(FileKind.contentTypeOf("x.unknown")).isEqualTo("application/octet-stream");
      assertThat(FileKind.contentTypeOf("noextension")).isEqualTo("application/octet-stream");
    }
  }

  private MultipartFile file(String filename) {
    return new MockMultipartFile("part", filename, null, "content".getBytes());
  }
}

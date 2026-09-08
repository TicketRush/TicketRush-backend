package com.ticketrush.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.util.FileKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 공개 URL 조립 규칙을 고정한다(#636).
 *
 * <p>Docker가 필요 없는 순수 단위 테스트로 둔다. varchar(255) 가드는 컨테이너와 무관한 회귀를 막는 것인데, LocalStack 통합 테스트에 두면
 * Docker가 없는 환경에서 클래스가 통째로 스킵될 때 가드도 함께 사라진다.
 */
class S3PropertiesTest {

  @Test
  @DisplayName("public-base-url 이 비어 있으면 버킷·리전으로 조립한다")
  void assemblesBaseUrlWhenNotGiven() {
    S3Properties properties = properties(null);

    assertThat(properties.toPublicUrl("performances/3d/abc.glb"))
        .isEqualTo(
            "https://ticketrush-assets.s3.ap-northeast-2.amazonaws.com/performances/3d/abc.glb");
  }

  @Test
  @DisplayName("public-base-url 을 지정하면 그것을 쓴다 — 끝의 슬래시는 있어도 없어도 된다")
  void prefersExplicitBaseUrl() {
    assertThat(properties("https://cdn.ticketrush.store").toPublicUrl("a/b.png"))
        .isEqualTo("https://cdn.ticketrush.store/a/b.png");
    assertThat(properties("https://cdn.ticketrush.store/").toPublicUrl("a/b.png"))
        .isEqualTo("https://cdn.ticketrush.store/a/b.png");
  }

  @Test
  @DisplayName("빈 문자열도 미지정으로 본다 — .env 의 빈 줄이 그대로 주입되기 때문이다")
  void treatsBlankAsNotGiven() {
    assertThat(properties("").toPublicUrl("a/b.png")).startsWith("https://ticketrush-assets.s3.");
    assertThat(properties("   ").toPublicUrl("a/b.png"))
        .startsWith("https://ticketrush-assets.s3.");
  }

  /**
   * 운영 버킷 형태의 공개 URL이 컬럼에 들어가는지 고정한다.
   *
   * <p>{@code image_main_url}·{@code image3d_url}·{@code performance_images.image_url}이 모두 {@code
   * varchar(255)}이고, prod는 {@code ddl-auto: validate}라 넘기면 수동 DDL이 필요하다. 키 prefix가 깊어지거나 버킷명이 길어지는
   * 회귀를 여기서 잡는다.
   */
  @Test
  @DisplayName("운영 버킷 형태의 공개 URL 이 varchar(255) 에 들어간다")
  void productionUrlFitsInColumn() {
    // 운영에서 쓰는 것과 같은 길이의 버킷명(계정 ID·리전 접미사 포함).
    S3Properties production = new S3Properties();
    production.setBucket("ticketrush-assets-prod-000000000000-ap-northeast-2-an");
    production.setRegion("ap-northeast-2");

    // 가장 긴 조합: 갤러리 prefix + UUID + 네 글자 확장자.
    String longestKey =
        FileKind.GALLERY.newObjectKey(
            new MockMultipartFile("part", "photo.jpeg", null, "content".getBytes()));

    String url = production.toPublicUrl(longestKey);

    assertThat(url).startsWith("https://").doesNotContain("?");
    assertThat(url.length()).isLessThanOrEqualTo(255);
  }

  private S3Properties properties(String publicBaseUrl) {
    S3Properties properties = new S3Properties();

    properties.setBucket("ticketrush-assets");
    properties.setRegion("ap-northeast-2");
    properties.setPublicBaseUrl(publicBaseUrl);

    return properties;
  }
}

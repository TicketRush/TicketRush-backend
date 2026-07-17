package com.ticketrush.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 내부 API 토큰이 설정 파일에 선언돼 있는지 검증한다 (#399).
 *
 * <p>{@code SecurityConfig}가 {@code InternalApiTokenFilter}를 등록하는데 {@code
 * custom.security.internal-token}이 비어 있으면, 필터는 기대 토큰이 없다는 이유로 <b>모든 내부 호출을 403으로 거절한다</b>. 그러면
 * booking-service의 취소·재환불이 전부 503으로 실패하고, ticket-service의 입장 검증(booking 동기 조회)도 함께 죽는다.
 *
 * <p>컨트롤러 슬라이스 테스트는 {@code @TestPropertySource}로 토큰을 주입하므로 이 공백을 잡지 못한다 — 테스트는 전부 초록인데 기능은 동작하지
 * 않는다. 그래서 <b>실제 설정 파일</b>을 읽어 키의 존재를 직접 확인한다.
 */
class InternalApiTokenConfigTest {

  private static final String INTERNAL_TOKEN_KEY = "custom.security.internal-token";

  private boolean declaresInternalToken(String yaml) throws IOException {
    return new YamlPropertySourceLoader()
        .load(yaml, new ClassPathResource(yaml)).stream()
            .anyMatch(source -> source.containsProperty(INTERNAL_TOKEN_KEY));
  }

  @Test
  @DisplayName("application.yml이 내부 API 토큰을 선언한다 — 없으면 모든 내부 호출이 403이 된다")
  void base_config_declares_internal_token() throws IOException {
    assertThat(declaresInternalToken("application.yml")).isTrue();
  }

  @Test
  @DisplayName("application-prod.yml이 내부 API 토큰을 기본값 없이 선언한다 — 미주입 시 기동에 실패해야 한다")
  void prod_config_declares_internal_token_without_default() throws IOException {
    PropertySource<?> prod =
        new YamlPropertySourceLoader()
            .load("application-prod.yml", new ClassPathResource("application-prod.yml"))
            .getFirst();

    assertThat(prod.containsProperty(INTERNAL_TOKEN_KEY)).isTrue();
    // 기본값(:)이 있으면 운영에서 토큰 미주입을 조용히 넘겨 403 장애로 이어진다.
    assertThat(prod.getProperty(INTERNAL_TOKEN_KEY)).isEqualTo("${INTERNAL_API_TOKEN}");
  }
}

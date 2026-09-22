package com.ticketrush.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 내부 API 토큰이 <b>비어 있으면 기동에 실패하는지</b> 검증한다 (#678).
 *
 * <p>ticket-service의 {@code InternalApiTokenConfigTest}는 설정 파일에 키가 <i>선언</i>됐는지만 본다. 그런데 운영 장애는 선언이
 * 아니라 <b>주입된 값</b>에서 났다 — {@code env_file}은 {@code INTERNAL_API_TOKEN=}처럼 값이 빈 줄도 '설정됨'으로 넘기므로
 * {@code ${INTERNAL_API_TOKEN}}의 플레이스홀더 fail-fast가 걸리지 않고, 기동은 성공한 채 {@code
 * InternalApiTokenFilter}가 모든 내부 호출을 403으로 떨군다. 그래서 여기서는 실제 바인딩 결과를 검증한다.
 */
class CustomSecurityPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  @DisplayName("실패: 내부 토큰이 비어 있으면 컨텍스트 로딩이 실패한다")
  void fails_fast_when_internal_token_is_blank(String token) {
    contextRunner
        .withPropertyValues("custom.security.internal-token=" + token)
        .run(
            context -> assertThat(context).hasFailed().getFailure().satisfies(REJECTS_BLANK_TOKEN));
  }

  @Test
  @DisplayName("실패: 내부 토큰 키 자체가 없으면 컨텍스트 로딩이 실패한다")
  void fails_fast_when_internal_token_is_absent() {
    contextRunner.run(
        context -> assertThat(context).hasFailed().getFailure().satisfies(REJECTS_BLANK_TOKEN));
  }

  @Test
  @DisplayName("성공: 내부 토큰이 주입되면 그대로 바인딩된다")
  void binds_internal_token_when_present() {
    contextRunner
        .withPropertyValues("custom.security.internal-token=real-token")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(CustomSecurityProperties.class).getInternalToken())
                  .isEqualTo("real-token");
            });
  }

  /**
   * 바인딩 실패의 <b>어느 필드 때문인지</b>까지 못 박는다. {@code ConfigurationPropertiesBindException}의 최상위 메시지는
   * prefix만 담고 위반 필드는 원인 예외에 있으므로, 스택 전체에서 확인해야 한다. 여기를 느슨하게 두면 다른 필드가 깨져도 이 테스트가 초록이 된다.
   */
  private static final ThrowingConsumer<Throwable> REJECTS_BLANK_TOKEN =
      failure ->
          assertThat(failure)
              .isInstanceOf(ConfigurationPropertiesBindException.class)
              .hasStackTraceContaining("internalToken");

  @EnableConfigurationProperties(CustomSecurityProperties.class)
  static class TestConfig {}
}

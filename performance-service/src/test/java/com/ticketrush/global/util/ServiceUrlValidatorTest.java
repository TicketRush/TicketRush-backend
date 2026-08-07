package com.ticketrush.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 이 검증기는 "설정이 틀리면 그 필드만 빈다"는 fail-open 성질이 성립하기 위한 단일 관문이다. 여기서 통과시킨 값이 요청 시점에 {@code
 * IllegalArgumentException}이 되면 그 예외는 {@code RestClientException}이 아니라서 클라이언트의 catch를 뚫고 API 전체를
 * 500으로 만든다. 그래서 경계를 테스트로 못 박는다.
 */
class ServiceUrlValidatorTest {

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "   ",
        // 스킴 누락 — compose 서비스명을 그대로 넣는 흔한 오설정
        "booking-service:8084",
        "//booking-service:8084",
        // 호스트 없음
        "http://",
        // http(s)가 아님
        "ftp://booking-service:8084",
        // 포트가 숫자가 아니면 URI 파싱 단계에서 호스트를 못 잡는다
        "http://booking-service:abc",
        // 언더스코어 호스트는 JDK HttpClient가 거부한다(URI.getHost()가 null) — 거절이 정답이다
        "http://booking_service:8084",
        // 앞뒤 공백: 다듬어서 통과시키면 정작 RestClient에는 원본이 넘어가 요청 시점에 터진다
        " http://booking-service:8084",
        "http://booking-service:8084 ",
        "\thttp://booking-service:8084\n"
      })
  @DisplayName("호출에 쓸 수 없는 URL은 거절한다")
  void isUsable_rejectsUnusableUrls(String url) {
    assertThat(ServiceUrlValidator.isUsable(url)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://booking-service:8084",
        "https://booking-service:8084",
        "http://localhost:8084",
        // 스킴 대소문자는 무관하다
        "HTTP://booking-service:8084",
        "http://booking-service"
      })
  @DisplayName("http(s) 절대 주소는 통과시킨다")
  void isUsable_acceptsAbsoluteHttpUrls(String url) {
    assertThat(ServiceUrlValidator.isUsable(url)).isTrue();
  }
}

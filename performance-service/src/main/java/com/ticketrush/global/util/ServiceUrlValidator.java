package com.ticketrush.global.util;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.util.StringUtils;

/**
 * 아웃바운드 서비스 base URL이 실제로 호출 가능한 형태인지 판정한다 (#563).
 *
 * <p><b>비어 있지 않은 것만으로는 부족하다.</b> {@code BOOKING_SERVICE_URL=booking-service:8084}처럼 스킴을 빠뜨린 값은
 * 문자열로는 멀쩡해서 {@code hasText} 검사를 통과하지만, 요청 시점에 {@code HttpRequest.newBuilder}가 {@code
 * IllegalArgumentException}을 던진다. 그 예외는 {@code RestClientException}이 아니라서 클라이언트의 fail-open catch를
 * 그대로 뚫고 나가고, 결국 필드 하나가 아니라 <b>API 전체가 500</b>이 된다.
 *
 * <p>즉 이 검사는 스타일 문제가 아니라 fail-open이 성립하기 위한 전제다. 호출 전에 여기서 걸러야 "설정이 틀리면 그 필드만 빈다"는 성질이 유지된다.
 */
public final class ServiceUrlValidator {

  private ServiceUrlValidator() {}

  /**
   * 호출에 쓸 수 있는 base URL인지 판정한다.
   *
   * <p>절대 URI이면서 스킴이 http(s)이고 호스트가 있어야 한다. 셋 중 하나라도 어긋나면 요청 단계에서 예외가 되므로 아예 호출하지 않는 편이 낫다.
   *
   * <p><b>다듬지 않은 원본을 그대로 검사한다.</b> {@code trim()}을 하면 앞뒤 공백이 섞인 값이 검사를 통과하는데, 정작 {@code
   * RestClient}에 넘어가는 것은 공백이 붙은 원본이라 요청 시점에 {@code Illegal character in scheme name} / {@code Bad
   * authority}로 터진다 — 검사한 문자열과 쓰는 문자열이 달라지는 순간 이 관문은 무의미해진다. 값 끝 공백은 {@code env_file}에서 흔한 오설정이므로
   * 통과시키지 않고 거절한다.
   */
  public static boolean isUsable(String url) {
    if (!StringUtils.hasText(url)) {
      return false;
    }

    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme();
      return uri.isAbsolute()
          && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
          && StringUtils.hasText(uri.getHost());
    } catch (URISyntaxException e) {
      return false;
    }
  }
}

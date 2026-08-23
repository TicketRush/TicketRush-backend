package com.ticketrush.global.config;

import com.ticketrush.global.util.ServiceUrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * performance-service의 아웃바운드 HTTP 클라이언트 (#563).
 *
 * <p>이 서비스의 <b>첫 동기 호출</b>이다. 지금까지 performance는 불려 가기만 했고 부르지 않았다. 관리자 대시보드가 매출(예매)과 좌석 점유율(좌석)을 함께
 * 그려야 하는데, ADR 0003이 크로스 도메인 조인을 금지하므로 API 호출로만 얻을 수 있다.
 *
 * <p><b>URL이 비어도 기동을 막지 않는다.</b> payment의 같은 설정은 URL 결손 시 {@code IllegalStateException}으로 기동을
 * 실패시키는데(#490), 그쪽은 fail-closed 결제 확정 경로라 조용히 새는 것이 곧 전건 503이었다. 여기는 정반대다 — 이 클라이언트들이 죽어도 응답의 해당
 * 필드만 비고, 반대로 기동을 막으면 <b>공연 목록·상세 같은 사용자 공개 API가 관리자 대시보드 설정 하나 때문에 함께 죽는다.</b> 장애 반경이 훨씬 큰 쪽을 택할
 * 이유가 없다. 대신 기동 시점에 경고를 남겨 설정 누락이 드러나게 한다.
 *
 * <p><b>다만 "해당 필드만 빈다"는 것은 상대가 빠르게 죽을 때의 이야기다.</b> 연결이 거부되면 그렇지만, 연결은 되고 응답이 늦는 형태라면 요청 하나당 톰캣 스레드를
 * read 타임아웃만큼 붙잡는다. #176 이후 좌석 호출은 무인증 공개 목록에도 붙었고 필터·커서 요청은 캐시를 우회하므로, 스레드 풀이 마르면 좌석 필드가 아니라 공연
 * 상세·배너까지 함께 degrade한다. 이 증폭 경로는 아래 타임아웃 항목의 후속 이슈에서 함께 다룬다.
 *
 * <p>타임아웃은 대상별로 나눈다(컨벤션: 호출 대상 2개 이상). read를 3초로 잡은 것은 두 호출이 모두 <b>전건 GROUP BY 집계</b>라 단건 조회
 * 선례(1~2초)보다 길게 걸리기 때문이다. 관리자 경로라 호출 빈도가 낮아 톰캣 스레드를 오래 잡는 위험도 그만큼 작다는 것이 이 값의 근거였다. <b>#176으로 좌석
 * 호출이 공개 공연 목록에도 붙어 그 전제는 더는 성립하지 않는다.</b> 무필터 첫 페이지는 목록 캐시가 눌러 주지만 필터·커서가 걸린 요청은 매번 이 타임아웃에 노출된다 —
 * 공개 경로 전용 타임아웃 분리는 부하 실측과 함께 후속 이슈로 둔다.
 */
@Slf4j
@Configuration
public class RestClientConfig {

  @Bean
  public RestClient bookingServiceRestClient(
      @Value("${service.booking.url:}") String bookingServiceUrl,
      @Value("${service.booking.connect-timeout-ms:1000}") long connectTimeoutMs,
      @Value("${service.booking.read-timeout-ms:3000}") long readTimeoutMs) {
    warnIfUnusable(
        bookingServiceUrl, "service.booking.url", "BOOKING_SERVICE_URL", "관리자 대시보드의 매출·판매 집계");
    return RestClient.builder()
        .baseUrl(bookingServiceUrl)
        .requestFactory(RestClientFactorySupport.withTimeouts(connectTimeoutMs, readTimeoutMs))
        .build();
  }

  @Bean
  public RestClient seatServiceRestClient(
      @Value("${service.seat.url:}") String seatServiceUrl,
      @Value("${service.seat.connect-timeout-ms:1000}") long connectTimeoutMs,
      @Value("${service.seat.read-timeout-ms:3000}") long readTimeoutMs) {
    warnIfUnusable(
        seatServiceUrl,
        "service.seat.url",
        "SEAT_SERVICE_URL",
        "관리자 대시보드의 좌석 점유율과 공개 공연 목록의 게이지바(잔여 좌석)");
    return RestClient.builder()
        .baseUrl(seatServiceUrl)
        .requestFactory(RestClientFactorySupport.withTimeouts(connectTimeoutMs, readTimeoutMs))
        .build();
  }

  /**
   * 설정 누락·오류를 기동 로그로 드러낸다. 호출 자체를 막는 것은 각 클라이언트의 몫이다.
   *
   * <p>비어 있는 값뿐 아니라 <b>스킴이 빠진 값</b>({@code booking-service:8084})도 경고 대상이다. 그런 값은 문자열로는 멀쩡해 보여
   * 통과하지만 요청 시점에 {@code IllegalArgumentException}이 되고, 그건 {@code RestClientException}이 아니라서 클라이언트의
   * fail-open catch를 뚫고 500이 된다. 기동 시점에 드러나지 않으면 관리자가 대시보드를 열어야만 알 수 있고, #176 이후로는 사용자 메인 화면의 게이지바가
   * 전 공연에서 조용히 사라지는 형태로도 나타난다.
   */
  private void warnIfUnusable(String url, String property, String envVar, String affected) {
    if (!ServiceUrlValidator.isUsable(url)) {
      log.warn(
          "[RestClientConfig] {} 가 비어 있거나 http(s) 절대 주소가 아니라 {} 이(가) 항상 비게 됩니다. "
              + "(현재 값: '{}') {} 환경변수를 대상 서비스 '직접' 주소로 스킴을 포함해 설정하세요"
              + "(게이트웨이는 내부 API를 라우팅하지 않습니다).",
          property,
          affected,
          url,
          envVar);
    }
  }
}

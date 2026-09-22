package com.ticketrush.global.config;

import com.ticketrush.global.util.ServiceUrlValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

  @Bean
  public RestClient seatServiceRestClient(
      @Value("${service.seat.url}") String seatServiceUrl,
      @Value("${service.http.connect-timeout-ms:3000}") long connectTimeoutMs,
      @Value("${service.http.read-timeout-ms:10000}") long readTimeoutMs) {
    return RestClient.builder()
        .baseUrl(seatServiceUrl)
        .requestFactory(RestClientFactorySupport.withTimeouts(connectTimeoutMs, readTimeoutMs))
        .build();
  }

  /**
   * 예매 조회 보강(좌석 번호) 전용 클라이언트 (#560). 쓰기용 {@code seatServiceRestClient}와 달리 공용 예산(3s/10s)을 쓰지 않는다 —
   * 같은 요청 안에서 공연 보강 예산 위에 얹히므로, 둘을 합치면 조회 하나가 톰캣 스레드를 13초까지 붙잡을 수 있다(ADR 0005가 경고한 그 값이다). 실패해도 부분
   * 응답으로 계속 가는 경로라 공연 쪽과 같은 논리로 빨리 포기한다.
   */
  @Bean
  public RestClient seatQueryRestClient(
      @Value("${service.seat.url}") String seatServiceUrl,
      @Value("${service.seat.query-connect-timeout-ms:1000}") long connectTimeoutMs,
      @Value("${service.seat.query-read-timeout-ms:2000}") long readTimeoutMs) {
    return RestClient.builder()
        .baseUrl(seatServiceUrl)
        .requestFactory(RestClientFactorySupport.withTimeouts(connectTimeoutMs, readTimeoutMs))
        .build();
  }

  /**
   * 예매 조회 보강(공연 필드) 전용 클라이언트 (#560). 공용 타임아웃(3s/10s) 대신 짧은 예산을 쓴다 — 실패해도 부분 응답(공연 필드 null)으로 계속 가는
   * 경로라 빨리 포기하는 것이 옳고, 목록 조회는 공연 수만큼 순차 호출하므로 건당 예산이 그대로 곱해진다.
   */
  @Bean
  public RestClient performanceServiceRestClient(
      @Value("${service.performance.url}") String performanceServiceUrl,
      @Value("${service.performance.connect-timeout-ms:1000}") long connectTimeoutMs,
      @Value("${service.performance.read-timeout-ms:2000}") long readTimeoutMs) {
    return RestClient.builder()
        .baseUrl(performanceServiceUrl)
        .requestFactory(RestClientFactorySupport.withTimeouts(connectTimeoutMs, readTimeoutMs))
        .build();
  }

  /**
   * 취소 경로의 입장권 판정 전용 클라이언트. 공용 타임아웃(3s/10s)이 아니라 훨씬 짧은 예산을 쓴다 — 판정에 필요한 건 단일 행 조회 하나인데,
   * ticket-service가 느려질 때 취소 요청 하나가 톰캣 스레드를 13초까지 붙잡으면 예매 경로 전체가 함께 마른다.
   *
   * <p><b>주소가 쓸 수 없는 값이면 기동을 막는다</b> (#678). 이 클라이언트만 다른 빈들과 달리 fail-closed 경로에 놓인다 — 조회가 실패하면
   * {@code TicketRestClient}가 판정을 포기하고 {@code BOOKING_503_001}로 취소·환불을 거절하므로(ADR 5), 주소가 틀리면 그 기능이
   * <b>전부</b> 죽는다. 그런데 잘못된 주소로도 빈 생성은 성공해 애플리케이션이 그대로 뜨고, 사용자가 환불을 눌러야만 드러난다.
   *
   * <p>비어 있는 값뿐 아니라 스킴이 빠진 값({@code ticket-service:8087})도 거절한다. 그런 값은 문자열로는 멀쩡해 보이지만 요청 시점에 {@code
   * IllegalArgumentException}이 되고, 그건 {@code RestClientException}이 아니라서 클라이언트의 catch를 뚫고 원시 500이
   * 된다.
   *
   * <p>(한계) 형식이 맞고 <b>대상만 틀린</b> 값({@code http://localhost:8087}, 게이트웨이 주소)은 여기서 걸러지지 않는다. 게이트웨이는
   * {@code /api/v1/internal/**}를 라우팅하지 않으므로 그 경우는 {@code TICKET_404_001이 아닌 404} 로그로 드러난다.
   */
  @Bean
  public RestClient ticketServiceRestClient(
      @Value("${service.ticket.url}") String ticketServiceUrl,
      @Value("${service.ticket.connect-timeout-ms:1000}") long connectTimeoutMs,
      @Value("${service.ticket.read-timeout-ms:2000}") long readTimeoutMs) {
    if (!ServiceUrlValidator.isUsable(ticketServiceUrl)) {
      throw new IllegalStateException(
          "service.ticket.url 이 비어 있거나 http(s) 절대 주소가 아닙니다 (현재 값: '"
              + ticketServiceUrl
              + "'). TICKET_SERVICE_URL 환경변수를 ticket-service '직접' 주소로 스킴을 포함해 설정하세요"
              + "(예: http://ticket-service:8087 — 게이트웨이는 내부 API를 라우팅하지 않습니다). "
              + "이 값이 틀리면 예매 취소와 환불이 전부 BOOKING_503_001로 실패합니다.");
    }

    return RestClient.builder()
        .baseUrl(ticketServiceUrl)
        .requestFactory(RestClientFactorySupport.withTimeouts(connectTimeoutMs, readTimeoutMs))
        .build();
  }

  /**
   * 관리자 예매 목록 보강(예매자 이름·이메일) 전용 클라이언트 (#561). 공연·좌석 보강과 같은 짧은 예산을 쓴다 — 실패해도 부분 응답(예매자 필드 null)으로 계속
   * 가는 경로이고, 같은 요청 안에서 세 보강 예산이 직렬로 얹히므로 하나라도 길면 합이 그대로 커진다.
   */
  @Bean
  public RestClient userServiceRestClient(
      @Value("${service.user.url}") String userServiceUrl,
      @Value("${service.user.connect-timeout-ms:1000}") long connectTimeoutMs,
      @Value("${service.user.read-timeout-ms:2000}") long readTimeoutMs) {
    return RestClient.builder()
        .baseUrl(userServiceUrl)
        .requestFactory(RestClientFactorySupport.withTimeouts(connectTimeoutMs, readTimeoutMs))
        .build();
  }
}

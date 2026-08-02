package com.ticketrush.global.config;

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
   */
  @Bean
  public RestClient ticketServiceRestClient(
      @Value("${service.ticket.url}") String ticketServiceUrl,
      @Value("${service.ticket.connect-timeout-ms:1000}") long connectTimeoutMs,
      @Value("${service.ticket.read-timeout-ms:2000}") long readTimeoutMs) {
    return RestClient.builder()
        .baseUrl(ticketServiceUrl)
        .requestFactory(RestClientFactorySupport.withTimeouts(connectTimeoutMs, readTimeoutMs))
        .build();
  }
}

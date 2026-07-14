package com.ticketrush.global.config;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

  @Bean
  @ConditionalOnProperty(prefix = "payment.pg.toss", name = "enabled", havingValue = "true")
  public RestClient tossPaymentRestClient(
      @Value("${payment.pg.toss.base-url:}") String baseUrl,
      @Value("${payment.pg.toss.secret-key:}") String secretKey,
      @Value("${payment.pg.toss.connect-timeout-ms:3000}") long connectTimeoutMs,
      @Value("${payment.pg.toss.read-timeout-ms:10000}") long readTimeoutMs) {

    if (!StringUtils.hasText(baseUrl)) {
      throw new IllegalStateException(
          "payment.pg.toss.enabled=true 인데 payment.pg.toss.base-url 가 비어있습니다. "
              + "TOSS_PAYMENTS_BASE_URL 환경변수를 설정하세요.");
    }
    if (!StringUtils.hasText(secretKey)) {
      throw new IllegalStateException(
          "payment.pg.toss.enabled=true 인데 payment.pg.toss.secret-key 가 비어있습니다. "
              + "TOSS_PAYMENTS_SECRET_KEY 환경변수를 설정하세요.");
    }

    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

    String basicAuth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(factory)
        .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth)
        .build();
  }

  /**
   * 취소 경로의 입장권 판정 전용 클라이언트 (#416). 공용/PG 타임아웃이 아니라 훨씬 짧은 예산을 쓴다 — 판정에 필요한 건 단일 행 조회 하나인데,
   * ticket-service가 느려질 때 취소 요청 하나가 톰캣 스레드를 오래 붙잡으면 결제 경로 전체가 함께 마른다.
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

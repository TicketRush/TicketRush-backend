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
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

  @Bean
  @ConditionalOnProperty(prefix = "payment.pg.toss", name = "secret-key")
  public RestClient tossPaymentRestClient(
      @Value("${payment.pg.toss.base-url}") String baseUrl,
      @Value("${payment.pg.toss.secret-key}") String secretKey,
      @Value("${payment.pg.toss.connect-timeout-ms:3000}") long connectTimeoutMs,
      @Value("${payment.pg.toss.read-timeout-ms:10000}") long readTimeoutMs) {

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
}

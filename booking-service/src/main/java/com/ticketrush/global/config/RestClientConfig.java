package com.ticketrush.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

  @Bean
  public RestClient seatServiceRestClient(
      @Value("${service.seat.base-url}") String seatServiceUrl,
      @Value("${service.http.connect-timeout-ms:3000}") long connectTimeoutMs,
      @Value("${service.http.read-timeout-ms:10000}") long readTimeoutMs) {
    return RestClient.builder()
        .baseUrl(seatServiceUrl)
        .requestFactory(RestClientFactorySupport.withTimeouts(connectTimeoutMs, readTimeoutMs))
        .build();
  }
}

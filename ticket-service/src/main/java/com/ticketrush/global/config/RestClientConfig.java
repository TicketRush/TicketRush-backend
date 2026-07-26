package com.ticketrush.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

  @Bean
  public RestClient bookingServiceRestClient(
      @Value("${service.booking.url}") String bookingServiceUrl,
      @Value("${service.http.connect-timeout-ms:1000}") long connectTimeoutMs,
      @Value("${service.http.read-timeout-ms:1000}") long readTimeoutMs) {
    return RestClient.builder()
        .baseUrl(bookingServiceUrl)
        .requestFactory(RestClientFactorySupport.withTimeouts(connectTimeoutMs, readTimeoutMs))
        .build();
  }
}

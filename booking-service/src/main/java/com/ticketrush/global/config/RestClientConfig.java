package com.ticketrush.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

  @Bean
  public RestClient seatServiceRestClient(@Value("${service.seat.url}") String seatServiceUrl) {
    return RestClient.builder().baseUrl(seatServiceUrl).build();
  }
}

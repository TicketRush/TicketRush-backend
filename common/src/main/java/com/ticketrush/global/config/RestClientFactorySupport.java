package com.ticketrush.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * 서비스 간 동기 호출용 {@link org.springframework.web.client.RestClient} 에 connect/read 타임아웃을 적용한 {@link
 * ClientHttpRequestFactory} 를 생성하는 공통 헬퍼.
 *
 * <p>각 서비스의 {@code RestClientConfig} 가 빈을 만들 때 호출한다. 공통 모듈에 {@code @Bean} 으로 두면 컴포넌트 스캔으로 전 서비스에
 * 주입/충돌하므로 정적 유틸로 제공한다.
 */
public final class RestClientFactorySupport {

  private RestClientFactorySupport() {}

  /**
   * connect/read 타임아웃이 설정된 {@link ClientHttpRequestFactory} 를 생성한다.
   *
   * @param connectTimeoutMs 연결 타임아웃(ms)
   * @param readTimeoutMs 읽기 타임아웃(ms)
   */
  public static ClientHttpRequestFactory withTimeouts(long connectTimeoutMs, long readTimeoutMs) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
    return factory;
  }
}

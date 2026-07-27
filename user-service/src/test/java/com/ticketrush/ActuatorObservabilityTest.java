package com.ticketrush;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * #500 의 완료 조건을 CI 에서 지키는 회귀 테스트.
 *
 * <p>둘 다 실제 서블릿 컨테이너를 띄워야만 드러난다 — Redis 를 쓰지 않는 서비스에서 health 가 UP 인지, 톰캣 스레드 메트릭이 실제로 바인딩되는지. 설정 한
 * 줄이 조용히 무효가 되면 즉시 깨진다(#500 에서 exclude 의 FQCN 이 Boot 4.0 경로가 아니어서 통째로 무시된 적이 있다).
 */
@ActiveProfiles("test")
@SpringBootTest(
    classes = UserServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorObservabilityTest {

  @LocalServerPort private int port;

  @Autowired private MeterRegistry meterRegistry;

  /** Redis 를 쓰지 않는 서비스이므로 Redis health contributor 가 붙어서는 안 된다(#500). */
  @Test
  void healthIsUp() {
    assertThat(get("/actuator/health")).contains("\"status\":\"UP\"");
  }

  /**
   * server.tomcat.mbeanregistry.enabled 가 꺼지면 ThreadPool MBean 이 등록되지 않아 이 미터들이 통째로 사라진다(#500).
   * Prometheus 로는 각각 tomcat_threads_busy_threads · tomcat_threads_config_max_threads 로 노출된다 —
   * 엔드포인트가 아니라 미터로 보는 것은 micrometer-registry-prometheus 가 runtimeOnly 라 테스트 클래스패스에 없기 때문이다.
   */
  @Test
  void tomcatThreadMetricsAreBound() {
    assertThat(meterRegistry.find("tomcat.threads.busy").gauge()).isNotNull();
    assertThat(meterRegistry.find("tomcat.threads.config.max").gauge()).isNotNull();
  }

  private String get(String path) {
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
      return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

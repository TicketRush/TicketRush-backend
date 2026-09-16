package com.ticketrush;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 게이트웨이 자신의 문서도 실제 계약과 일치하는지 지킨다 (#658).
 *
 * <p>여기서 보는 것은 대기열 API 스키마뿐이다. 다른 서비스 문서는 게이트웨이를 그냥 지나간다(RewritePath 중계).
 *
 * <p>대기열은 {@code QUEUE_ENABLED=false} 로 꺼져 있어 아직 프론트 피해가 없지만, 연동(#472)이 시작되면 같은 불일치가 그대로 사고가 된다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
    classes = GatewayServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // 실제 서버를 띄우는 구성에서만 라우트 uri 의 services.*.host 가 해석돼야 한다. 컨텍스트만 올리는
    // GatewayServiceApplicationTests 가 이 값들 없이도 뜨는 것은 그래서다. 라우팅을 하지 않는
    // 테스트라 값 자체는 무엇이든 상관없다.
    properties = {
      "jwt.secret=test-secret-key-for-gateway-openapi-naming-test",
      "queue.enabled=false",
      "services.user.host=localhost",
      "services.auth.host=localhost",
      "services.performance.host=localhost",
      "services.booking.host=localhost",
      "services.payment.host=localhost",
      "services.seat.host=localhost",
      "services.ticket.host=localhost"
    })
class OpenApiDocumentNamingTest {

  private static final Pattern CAMEL_CASE = Pattern.compile(".*[a-z][A-Z].*");

  @LocalServerPort private int port;

  private JsonNode document;

  @BeforeAll
  void fetchDocument() throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build();
      String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
      document = JsonMapper.builder().build().readTree(body);
    }
  }

  /** 문서 전체를 훑어 {@code properties} 아래 이름을 모은다. 중첩·인라인 스키마도 빠뜨리지 않는다. */
  private void collectViolations(JsonNode node, String path, List<String> violations) {
    if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        collectViolations(node.get(i), path, violations);
      }
      return;
    }
    if (!node.isObject()) {
      return;
    }
    for (Map.Entry<String, JsonNode> entry : node.properties()) {
      String key = entry.getKey();
      JsonNode value = entry.getValue();

      if ("properties".equals(key) && value.isObject()) {
        for (Map.Entry<String, JsonNode> property : value.properties()) {
          String name = property.getKey();
          if (CAMEL_CASE.matcher(name).matches()) {
            violations.add(path + " -> " + name);
          }
          collectViolations(property.getValue(), path + "/" + name, violations);
        }
        continue;
      }
      collectViolations(value, path + "/" + key, violations);
    }
  }

  @Test
  @DisplayName("문서 어디에도 camelCase 프로퍼티가 남지 않는다")
  void schemaPropertiesAreSnakeCase() {
    List<String> violations = new ArrayList<>();
    collectViolations(document, "", violations);

    assertThat(violations)
        .as("문서가 camelCase 로 내보내는 필드 — 실제 응답은 snake_case 라 프론트가 읽지 못한다")
        .isEmpty();
  }

  @Test
  @DisplayName("대기열 응답과 공통 래퍼가 snake_case 로 나온다")
  void queueSchemasAreSnakeCase() {
    JsonNode schemas = document.path("components").path("schemas");

    // 스키마 이름은 springdoc 이 합성한 것이라, 먼저 존재를 확인해야 실패 원인을 구분할 수 있다.
    assertThat(schemas.path("EnqueueResponse").isObject()).isTrue();
    assertThat(schemas.path("ApiResponseEnqueueResponse").isObject()).isTrue();

    assertThat(schemas.path("EnqueueResponse").path("properties").propertyNames())
        .contains("waiting_token", "next_poll_after_seconds");
    assertThat(schemas.path("ApiResponseEnqueueResponse").path("properties").propertyNames())
        .contains("is_success", "trace_id");
  }
}

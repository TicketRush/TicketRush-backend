package com.ticketrush;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.util.S3UploadUtils;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 생성된 OpenAPI 문서의 필드명이 실제 HTTP 계약과 일치하는지 지킨다 (#658).
 *
 * <p>문서가 camelCase 로 나가고 실제 응답은 snake_case 이던 시절, 프론트가 문서를 믿고 구현하면 요청은 필드가 통째로 무시되고 응답은 undefined 가
 * 됐다. 둘 다 에러 없이 조용히 어긋나므로 사람 눈으로는 걸러지지 않는다.
 *
 * <p>실제 서블릿을 띄워 문서를 받는 이유는, 스키마 생성이 springdoc 자동구성 전체를 거쳐야 재현되기 때문이다. 컨버터만 따로 돌리는 단위 테스트는 그 체인을 태우지
 * 못한다.
 */
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // 클래스에 붙인 @EnableAutoConfiguration(exclude=)은 @SpringBootTest 가 설정 소스로 삼지 않아
    // 무시될 수 있다. 프로퍼티로 주면 확실하게 적용된다.
    properties =
        "spring.autoconfigure.exclude="
            + "io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration,"
            + "io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration")
class OpenApiDocumentNamingTest {

  private static final Pattern CAMEL_CASE = Pattern.compile(".*[a-z][A-Z].*");

  @LocalServerPort private int port;

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  private JsonNode document;

  @BeforeAll
  void fetchDocument() throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build();
      String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
      // 전역 JacksonConfig 를 타지 않는 매퍼로 읽는다. 문서를 있는 그대로 봐야 한다.
      document = JsonMapper.builder().build().readTree(body);
    }
  }

  /**
   * camelCase 로 남아도 되는 자리인지 판정한다.
   *
   * <p>multipart 파트명은 {@code @RequestPart} 의 파트명이라 JSON 프로퍼티가 아니다 — snake 로 바뀌면 그 문서를 믿은 클라이언트가 메인
   * 이미지 교체에 조용히 실패한다(#637). 이름만 보고 허용하면 다른 스키마에 같은 이름이 생겼을 때 진짜 위반을 덮어 주므로 자리까지 본다.
   */
  private boolean isCamelCaseByContract(String path, String name) {
    return "mainImage".equals(name) && path.endsWith("SwaggerBody");
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
          if (CAMEL_CASE.matcher(name).matches() && !isCamelCaseByContract(path, name)) {
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
  @DisplayName("공통 응답 래퍼도 snake_case 로 나온다")
  void commonResponseWrapperIsSnakeCase() {
    JsonNode wrapper =
        document.path("components").path("schemas").path("ApiResponsePerformanceDetailResponse");

    // 스키마 이름은 springdoc 이 합성한 것이라, 먼저 존재를 확인해야 실패 원인을 구분할 수 있다.
    assertThat(wrapper.isObject()).as("합성 래퍼 스키마를 찾지 못했다 — 이름 규칙이 바뀌었을 수 있다").isTrue();
    assertThat(wrapper.path("properties").propertyNames())
        .contains("is_success", "trace_id", "pagination_info");
  }

  @Test
  @DisplayName("multipart 파트명은 camelCase 그대로 둔다")
  void multipartPartNamesStayCamelCase() {
    for (String bodySchema :
        List.of("PerformanceCreateSwaggerBody", "PerformanceFileReplaceSwaggerBody")) {
      JsonNode schema = document.path("components").path("schemas").path(bodySchema);

      assertThat(schema.isObject()).as("%s 스키마를 찾지 못했다", bodySchema).isTrue();
      assertThat(schema.path("properties").propertyNames())
          .as("%s 의 파트명 — snake 로 바뀌면 그 파트만 조용히 전송에 실패한다", bodySchema)
          .contains("mainImage")
          .doesNotContain("main_image");
    }
  }

  @Test
  @DisplayName("쿼리·경로 파라미터 이름은 바뀌지 않는다")
  void parameterNamesAreUntouched() {
    List<String> names = new ArrayList<>();
    for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
        for (JsonNode parameter : operation.getValue().path("parameters")) {
          names.add(parameter.path("name").asString());
        }
      }
    }

    // 파라미터는 Jackson 이 아니라 스프링이 바인딩하므로 스키마 네이밍과 무관하다. 바뀌면 조용히 무시된다.
    assertThat(names)
        .contains("cursorId", "minPrice", "maxPrice")
        .doesNotContain("cursor_id", "min_price", "max_price");
  }

  @Test
  @DisplayName("문서 구조와 다형성 참조가 온전하다")
  void documentStructureIsIntact() {
    // 스키마 이름을 바꾸는 매퍼를 문서 직렬화에까지 쓰면 requestBody 가 request_body 가 되어 문서가 깨진다.
    assertThat(document.path("openapi").asString()).startsWith("3.1");
    assertThat(document.path("paths")).isNotEmpty();

    // sealed 타입의 하위 타입이 사라지면 oneOf 가 없는 스키마를 가리키는 깨진 참조만 남는다.
    assertThat(document.path("components").path("schemas").propertyNames())
        .contains("CursorInfo", "PageInfo");
  }
}

package com.ticketrush.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ticketrush.global.dto.response.ApiResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 스키마 이름 변환 규칙 자체를 고정한다 (#658).
 *
 * <p>서비스별 회귀 테스트가 결과물(생성된 문서)을 본다면, 이쪽은 규칙을 본다. 컨텍스트를 띄우지 않으므로 빠르고, 규칙이 깨진 이유를 바로 지목한다.
 *
 * <p>{@link ModelConverters#getInstance} 대신 인스턴스를 직접 만드는 것은 의도한 것이다. 그 싱글톤에 컨버터를 등록하면 같은 JVM 의 다른
 * 테스트까지 영향을 받는다.
 *
 * <p>다만 이 격리는 컨버터 목록까지다. 리졸버를 만들면 swagger 가 넘겨받은 매퍼에 자기 introspector 를 끼워 넣으므로 매퍼 쪽은 공유되고, 직접 만든
 * 인스턴스에는 springdoc 이 넣는 컨버터도 없다. 즉 여기서 태우는 것은 실제 문서 생성 경로가 아니라 이름 변환 규칙 그 자체다. 경로 전체를 태우는 검증은
 * performance-service 의 {@code OpenApiDocumentNamingTest} 가 한다.
 */
class SnakeCaseModelResolverTest {

  private Map<String, Schema> resolve(Class<?> type) {
    ModelConverters converters = new ModelConverters(true);
    converters.addConverter(new SnakeCaseModelResolver(Json31.mapper()).openapi31(true));
    return converters.readAll(type);
  }

  private Map<String, Schema> propertiesOf(Class<?> type) {
    return resolve(type).get(type.getSimpleName()).getProperties();
  }

  @Test
  @DisplayName("전 서비스가 쓰는 공통 응답 래퍼가 snake_case 로 나온다")
  void commonResponseWrapperIsSnakeCase() {
    Map<String, Schema> properties = propertiesOf(ApiResponse.class);

    assertThat(properties).containsKeys("is_success", "trace_id", "pagination_info");
    assertThat(properties).doesNotContainKeys("isSuccess", "traceId", "paginationInfo");
  }

  @Test
  @DisplayName("이름을 직접 적은 프로퍼티는 건드리지 않는다")
  void explicitlyNamedPropertiesAreKept() {
    Map<String, Schema> properties = propertiesOf(ExplicitlyNamed.class);

    // @JsonProperty 로 적은 이름이 곧 실제 계약이다(auth 의 refreshToken · user 의 socialId 가 이 경우다).
    assertThat(properties).containsKey("keptByJsonProperty");
    // @Schema(name=) 은 문서에 그 이름으로 내보내겠다는 뜻이다(multipart 파트명이 이 경로로 보호된다).
    assertThat(properties).containsKey("keptBySchemaName");
    // 아무것도 적지 않은 것만 바뀐다.
    assertThat(properties).containsKey("plain_property").doesNotContainKey("plainProperty");
  }

  @Test
  @DisplayName("required 목록도 바뀐 이름을 가리킨다")
  void requiredNamesFollowProperties() {
    Schema<?> schema = resolve(WithRequired.class).get(WithRequired.class.getSimpleName());

    // 여기가 어긋나면 문서상 없는 필드를 필수라고 말하게 된다.
    assertThat(schema.getRequired()).containsExactly("required_field");
  }

  static class ExplicitlyNamed {
    @JsonProperty("keptByJsonProperty")
    public String keptByJsonProperty;

    @io.swagger.v3.oas.annotations.media.Schema(name = "keptBySchemaName")
    public String keptBySchemaName;

    public String plainProperty;
  }

  static class WithRequired {
    @io.swagger.v3.oas.annotations.media.Schema(
        requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    public String requiredField;
  }
}

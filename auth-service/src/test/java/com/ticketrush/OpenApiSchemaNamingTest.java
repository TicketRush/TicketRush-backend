package com.ticketrush;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.config.SnakeCaseModelResolver;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이름을 직접 적은 필드가 스키마 네이밍 전략에 휩쓸리지 않는지 지킨다 (#658).
 *
 * <p>토큰 재발급 요청의 refreshToken 은 {@code @JsonProperty} 로 이름을 적은 것이라 **그 이름이 곧 실제 계약**이다. 전략이 여기까지 닿으면
 * 문서가 실제 요청과 어긋나고, 문서를 믿은 클라이언트는 조용히 인증에 실패한다.
 *
 * <p>컨텍스트를 띄우지 않는 이유는 이 서비스를 실제로 기동하려면 외부 연동 설정이 전부 필요하기 때문이다. 문서 생성 경로 전체를 태우는 검증은
 * performance-service 의 {@code OpenApiDocumentNamingTest} 가 한다.
 */
class OpenApiSchemaNamingTest {

  @Test
  @DisplayName("이름을 직접 적은 필드는 그대로 둔다")
  void explicitlyNamedPropertiesAreKept() {
    // getInstance 싱글톤에 등록하면 같은 JVM 의 다른 테스트까지 영향을 받는다.
    ModelConverters converters = new ModelConverters(true);
    converters.addConverter(new SnakeCaseModelResolver(Json31.mapper()).openapi31(true));

    Map<String, Schema> schemas =
        converters.readAll(
            com.ticketrush.boundedcontext.auth.app.dto.request.TokenReissueRequest.class);
    Map<String, Schema> properties = schemas.get("TokenReissueRequest").getProperties();

    assertThat(properties).containsKeys("refreshToken").doesNotContainKeys("refresh_token");
  }
}

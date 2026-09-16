package com.ticketrush.global.config;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Configuration
public class SwaggerConfig {

  @Bean
  public OpenAPI openAPI() {
    return new OpenAPI()
        .servers(List.of(new Server().url("http://localhost:8080")))

        // 1. Security 설정 추가
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))

        // 2. 전역 적용 (모든 API에 Authorization 붙음)
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
  }

  /**
   * 스키마 프로퍼티 이름을 실제 HTTP 계약과 맞추는 리졸버를 체인에 넣는다 (#658).
   *
   * <p>서비스마다 둘 필요는 없다. springdoc 이 컨텍스트의 ModelConverter 빈을 모아 체인에 끼워 넣으므로 common 의 이 빈 하나가 전 서비스에
   * 적용된다. 왜 매퍼에 전략을 걸지 않는지는 {@link SnakeCaseModelResolver} 참고.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public ModelResolver snakeCaseModelResolver() {
    // 이 우선순위는 체인의 "맨 뒤"를 뜻한다. swagger-core 가 컨버터를 목록 맨 앞에 끼워 넣기 때문에,
    // 먼저 오는 빈일수록 체인에서는 뒤로 밀린다. 값을 올려 앞으로 보내려 하면 오히려 반대로 동작해
    // 파일·다형성·3.1 표현을 처리하는 springdoc 컨버터들이 이 리졸버 뒤로 밀리고, 그러면 sealed 타입의
    // oneOf 가 가리키는 스키마가 사라진다. 기본 리졸버가 있던 자리를 그대로 쓰는 것이 맞다.

    // 매퍼는 swagger 가 쓰는 것을 그대로 넘긴다. 새 인스턴스를 주면 sealed 하위 타입 해석이 깨진다.
    // Json31·openapi31 은 문서 버전이 3.1 이라는 전제다. springdoc.api-docs.version 을 3.0 으로
    // 내리면 이 둘도 3.0 쪽(Json.mapper())으로 맞춰야 한다.
    return new SnakeCaseModelResolver(Json31.mapper()).openapi31(true);
  }
}

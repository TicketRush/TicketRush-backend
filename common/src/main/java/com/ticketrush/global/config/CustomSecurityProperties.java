package com.ticketrush.global.config;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

// application.yml에 있는 값을 Java 객체로 가져오는 역할
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "custom.security")
public class CustomSecurityProperties {

  /**
   * 서비스 간 내부 API 호출 전용 토큰. <b>비어 있으면 기동에 실패시킨다</b> (#678).
   *
   * <p>{@code InternalApiTokenFilter}는 기대 토큰이 공백이면 모든 내부 호출을 403으로 거절한다. 그러면 booking의 취소·환불이 전부
   * {@code BOOKING_503_001}로 실패하고 ticket의 입장 검증(booking 동기 조회)도 함께 죽는데, <b>애플리케이션 기동은 성공하므로 헬스체크도
   * CD도 이상을 감지하지 못한다.</b>
   *
   * <p>{@code application-prod.yml}이 {@code ${INTERNAL_API_TOKEN}}을 기본값 없이 선언한 것만으로는 이 상태를 막지 못한다 —
   * 플레이스홀더 fail-fast는 변수가 <i>없을</i> 때만 걸리는데, {@code env_file}은 {@code INTERNAL_API_TOKEN=}처럼 값이 빈
   * 줄도 '설정됨'으로 컨테이너에 넘긴다. 그래서 선언이 아니라 <b>바인딩된 값</b>을 여기서 검증한다.
   *
   * <p>같은 이유로 설정 파일을 읽는 {@code InternalApiTokenConfigTest}(ticket-service)도 이 공백은 잡지 못한다. 그 테스트는 키의
   * 선언 여부를 보는 다른 층이므로 함께 남겨 둔다.
   */
  @NotBlank private String internalToken;

  private boolean permitAll;
  private List<String> permitUrls = new ArrayList<>();
}

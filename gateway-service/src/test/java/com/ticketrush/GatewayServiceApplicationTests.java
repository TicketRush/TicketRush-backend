package com.ticketrush;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 게이트웨이 컨텍스트가 실제로 뜨는지 확인한다.
 *
 * <p>원래는 {@code classes} 가 이 테스트 클래스 자신을 가리키고 있어 애플리케이션 컨텍스트를 전혀 로드하지 않는 빈 테스트였다 — 라우트·필터·설정이 어떻게
 * 깨져도 통과했다. 대기열(#472)이 게이트웨이에 Redis 의존과 신규 필터를 들이면서 "뜨는지"가 실제 검증 항목이 됐다.
 *
 * <p>Redis는 lazy 연결이라 커넥션 없이도 컨텍스트는 뜬다. 실제 동작 검증은 {@code WaitingRoomGatewayTest}(Testcontainers)가
 * 한다.
 */
@SpringBootTest(
    classes = GatewayServiceApplication.class,
    properties = "jwt.secret=test-secret-key-for-gateway-context-load-test")
class GatewayServiceApplicationTests {

  @Test
  void contextLoads() {}
}

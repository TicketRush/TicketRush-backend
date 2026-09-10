package com.ticketrush.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "jwt.secret=test-secret-key-for-rate-limit-route-config-test",
      "queue.enabled=false",

      // application.yml의 서비스 host placeholder 해석용.
      // 실제 서비스로 요청을 보내지 않고 route definition만 검증한다.
      "services.user.host=localhost",
      "services.auth.host=localhost",
      "services.performance.host=localhost",
      "services.booking.host=localhost",
      "services.payment.host=localhost",
      "services.seat.host=localhost",
      "services.ticket.host=localhost"
    })
class RateLimitRouteConfigTest {

  private static final List<String> RATE_LIMIT_ROUTE_IDS =
      List.of(
          "payment-confirm-rate-limit",
          "auth-sensitive-rate-limit",
          "booking-create-rate-limit",
          "email-verification-send-rate-limit",
          "email-exists-rate-limit",
          "entry-verify-rate-limit",
          "seat-sse-rate-limit");

  @Autowired private RouteDefinitionLocator routeDefinitionLocator;

  @Test
  @DisplayName("Rate Limit 대상 7개 route가 모두 등록된다")
  void RateLimit_route_7개가_등록된다() {
    Map<String, RouteDefinition> routes = routesById();

    assertThat(routes.keySet()).containsAll(RATE_LIMIT_ROUTE_IDS);
  }

  @Test
  @DisplayName("Rate Limit 전용 route는 기존 broad route보다 높은 우선순위를 가진다")
  void RateLimit_route는_기존_route보다_우선한다() {
    Map<String, RouteDefinition> routes = routesById();

    assertThat(routes.get("payment-confirm-rate-limit").getOrder()).isEqualTo(-100);
    assertThat(routes.get("auth-sensitive-rate-limit").getOrder()).isEqualTo(-99);
    assertThat(routes.get("booking-create-rate-limit").getOrder()).isEqualTo(-98);
    assertThat(routes.get("email-verification-send-rate-limit").getOrder()).isEqualTo(-97);
    assertThat(routes.get("email-exists-rate-limit").getOrder()).isEqualTo(-96);
    assertThat(routes.get("entry-verify-rate-limit").getOrder()).isEqualTo(-95);
    assertThat(routes.get("seat-sse-rate-limit").getOrder()).isEqualTo(-94);

    assertThat(routes.get("payment-service").getOrder()).isZero();
    assertThat(routes.get("auth-service").getOrder()).isZero();
    assertThat(routes.get("booking-service").getOrder()).isZero();
  }

  @Test
  @DisplayName("결제 승인 Rate Limit은 POST /api/v1/payment/confirm에만 적용된다")
  void paymentConfirm_route_범위가_정확하다() {
    RouteDefinition route = routesById().get("payment-confirm-rate-limit");

    assertPathPredicate(route, "/api/v1/payment/confirm");
    assertMethodPredicate(route, "POST");
    assertRequestRateLimiter(route);
  }

  @Test
  @DisplayName("인증 Rate Limit은 login, social login, reissue에만 적용된다")
  void authSensitive_route_범위가_정확하다() {
    RouteDefinition route = routesById().get("auth-sensitive-rate-limit");

    assertPathPredicate(route, "/api/v1/auth/login,/api/v1/auth/social/login,/api/v1/auth/reissue");
    assertMethodPredicate(route, "POST");
    assertRequestRateLimiter(route);
  }

  @Test
  @DisplayName("예매 생성 Rate Limit은 POST /api/v1/booking에 적용된다")
  void bookingCreate_route_범위가_정확하다() {
    RouteDefinition route = routesById().get("booking-create-rate-limit");

    assertPathPredicate(route, "/api/v1/booking");
    assertMethodPredicate(route, "POST");
    assertRequestRateLimiter(route);
  }

  @Test
  @DisplayName("이메일 인증 발송 Rate Limit route가 정확하다")
  void emailVerification_route_범위가_정확하다() {
    RouteDefinition route = routesById().get("email-verification-send-rate-limit");

    assertPathPredicate(route, "/api/v1/auth/signup/email-verification/send");
    assertMethodPredicate(route, "POST");
    assertRequestRateLimiter(route);
  }

  @Test
  @DisplayName("이메일 존재 확인 Rate Limit route가 정확하다")
  void emailExists_route_범위가_정확하다() {
    RouteDefinition route = routesById().get("email-exists-rate-limit");

    assertPathPredicate(route, "/api/v1/user/exists/email");
    assertMethodPredicate(route, "GET");
    assertRequestRateLimiter(route);
  }

  @Test
  @DisplayName("입장 검표 Rate Limit은 POST /api/v1/entries/verify에 적용된다")
  void entryVerify_route_범위가_정확하다() {
    RouteDefinition route = routesById().get("entry-verify-rate-limit");

    assertPathPredicate(route, "/api/v1/entries/verify");
    assertMethodPredicate(route, "POST");
    assertRequestRateLimiter(route);
  }

  @Test
  @DisplayName("SSE Rate Limit route는 GET stream 경로이며 장기 연결 metadata를 유지한다")
  void sse_route_범위와_metadata가_정확하다() {
    RouteDefinition route = routesById().get("seat-sse-rate-limit");

    assertPathPredicate(route, "/api/v1/seat/*/seat-status/stream");
    assertMethodPredicate(route, "GET");
    assertRequestRateLimiter(route);

    assertThat(route.getMetadata())
        .containsEntry("response-timeout", -1)
        .containsEntry("connect-timeout", 5000);
  }

  @Test
  @DisplayName("payment webhook에는 별도의 Redis Rate Limit route를 만들지 않는다")
  void paymentWebhook은_Redis_RateLimit_대상이_아니다() {
    Map<String, RouteDefinition> routes = routesById();

    assertThat(routes.keySet())
        .noneMatch(id -> id.contains("webhook") && id.endsWith("-rate-limit"));

    RouteDefinition paymentConfirm = routes.get("payment-confirm-rate-limit");

    String path =
        findPredicate(paymentConfirm, "Path").getArgs().values().stream()
            .collect(Collectors.joining(","));

    assertThat(path).doesNotContain("/api/v1/payment/webhook");

    assertThat(routes).containsKey("payment-service");
  }

  @Test
  @DisplayName("모든 Rate Limit route는 userOrIpKeyResolver를 사용한다")
  void 모든_RateLimit_route가_동일한_KeyResolver를_사용한다() {
    Map<String, RouteDefinition> routes = routesById();

    for (String routeId : RATE_LIMIT_ROUTE_IDS) {
      FilterDefinition filter = findFilter(routes.get(routeId), "RequestRateLimiter");

      assertThat(filter.getArgs()).containsEntry("key-resolver", "#{@userOrIpKeyResolver}");
    }
  }

  private Map<String, RouteDefinition> routesById() {
    List<RouteDefinition> routes =
        routeDefinitionLocator.getRouteDefinitions().collectList().block(Duration.ofSeconds(5));

    assertThat(routes).isNotNull();

    return routes.stream().collect(Collectors.toMap(RouteDefinition::getId, Function.identity()));
  }

  private static void assertPathPredicate(RouteDefinition route, String expectedPath) {

    PredicateDefinition predicate = findPredicate(route, "Path");

    List<String> actual =
        predicate.getArgs().values().stream()
            .flatMap(value -> java.util.Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();

    List<String> expected =
        java.util.Arrays.stream(expectedPath.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();

    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
  }

  private static void assertMethodPredicate(RouteDefinition route, String expectedMethod) {

    PredicateDefinition predicate = findPredicate(route, "Method");

    assertThat(predicate.getArgs().values()).containsOnly(expectedMethod);
  }

  private static void assertRequestRateLimiter(RouteDefinition route) {
    FilterDefinition filter = findFilter(route, "RequestRateLimiter");

    assertThat(filter.getArgs())
        .containsEntry("key-resolver", "#{@userOrIpKeyResolver}")
        .containsKey("redis-rate-limiter.replenishRate")
        .containsKey("redis-rate-limiter.burstCapacity")
        .containsKey("redis-rate-limiter.requestedTokens");
  }

  private static PredicateDefinition findPredicate(RouteDefinition route, String name) {

    return route.getPredicates().stream()
        .filter(predicate -> predicate.getName().equals(name))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError(route.getId() + " route에 " + name + " predicate가 없습니다."));
  }

  private static FilterDefinition findFilter(RouteDefinition route, String name) {

    return route.getFilters().stream()
        .filter(filter -> filter.getName().equals(name))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError(route.getId() + " route에 " + name + " filter가 없습니다."));
  }
}

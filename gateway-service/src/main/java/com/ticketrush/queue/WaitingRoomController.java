package com.ticketrush.queue;

import com.ticketrush.dto.response.ApiResponse;
import com.ticketrush.exception.BusinessException;
import com.ticketrush.security.JwtTokenProvider;
import com.ticketrush.status.ErrorStatus;
import com.ticketrush.status.SuccessStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 대기열 API(ADR 0009).
 *
 * <p><b>이 컨트롤러가 게이트웨이 라우트가 아니라 어노테이션 핸들러인 것이 설계의 핵심이다.</b> {@code GlobalFilter} 는 라우트가 매칭됐을 때만 도는
 * {@code FilteringWebHandler} 가 실행하는데, {@code application.yml} 의 8개 라우트에 catch-all이 없어 {@code
 * /api/v1/queue/**} 는 어디에도 매칭되지 않는다. 따라서 {@code JwtAuthenticationFilter} 가 아예 실행되지 않는다 — ADR §4가
 * 요구한 "JWT 검증 체인을 태우지 않는다 / 요청당 로그를 남기지 않는다"를 코드 분기 없이 구조로 만족한다. 필터 order에 기대지 않으므로 Spring Cloud
 * Gateway 버전이 올라가도 깨지지 않는다. (springdoc이 {@code /swagger-ui/**} 를 같은 방식으로 이미 서빙하고 있다.)
 *
 * <p>그 대가로 게이트웨이가 주입하는 {@code X-User-Id} 헤더가 없다. 진입에서만 JWT를 직접 파싱하고, 이후 폴링은 불투명 대기 토큰만 대조한다.
 */
@RestController
@RequestMapping("/api/v1/queue")
public class WaitingRoomController {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String WAITING_TOKEN_HEADER = "X-Waiting-Token";

  private final WaitingRoomService waitingRoomService;
  private final JwtTokenProvider jwtTokenProvider;

  public WaitingRoomController(
      WaitingRoomService waitingRoomService, JwtTokenProvider jwtTokenProvider) {
    this.waitingRoomService = waitingRoomService;
    this.jwtTokenProvider = jwtTokenProvider;
  }

  /** 대기열 진입 — 순번 발급. 1인 1회라 여기서만 JWT 서명을 검증한다. */
  @PostMapping("/{performanceId}/enqueue")
  public Mono<ResponseEntity<ApiResponse<?>>> enqueue(
      @PathVariable Long performanceId,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

    return Mono.fromCallable(() -> resolveUserId(authorization))
        .flatMap(userId -> waitingRoomService.enqueue(performanceId, userId))
        .map(result -> ok(SuccessStatus.CREATED, result))
        .onErrorResume(BusinessException.class, e -> Mono.just(fail(e.getErrorStatus())));
  }

  /**
   * 대기열 상태 확인 — 순번·대기 인원·다음 폴링 시각, 허용선 안이면 입장 토큰.
   *
   * <p>1만 명이 폴링하는 유일한 경로다. 여기에 무언가를 더하기 전에 ADR §4를 다시 읽는다.
   */
  @GetMapping("/{performanceId}/status")
  public Mono<ResponseEntity<ApiResponse<?>>> status(
      @PathVariable Long performanceId,
      @RequestHeader(value = WAITING_TOKEN_HEADER, required = false) String waitingToken) {

    return waitingRoomService
        .status(performanceId, waitingToken)
        .map(result -> ok(SuccessStatus.OK, result))
        .onErrorResume(BusinessException.class, e -> Mono.just(fail(e.getErrorStatus())));
  }

  private Long resolveUserId(String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_TOKEN);
    }
    String token = authorization.substring(BEARER_PREFIX.length());
    if (!jwtTokenProvider.validateToken(token)) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_TOKEN);
    }
    if (!"access".equals(jwtTokenProvider.getType(token))) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_TOKEN_TYPE);
    }
    return jwtTokenProvider.getUserId(token);
  }

  // ApiResponse의 정적 팩토리는 ResponseEntity<ApiResponse<T>> / <ApiResponse<?>> 로 갈려 Mono 타입이
  // 안 맞는다. 와일드카드 하나로 통일하는 얇은 어댑터를 여기 둔다.
  private static ResponseEntity<ApiResponse<?>> ok(SuccessStatus status, Object result) {
    return new ResponseEntity<>(
        new ApiResponse<>(true, status.getCode(), status.getMessage(), null, result),
        status.getHttpStatus());
  }

  private static ResponseEntity<ApiResponse<?>> fail(ErrorStatus error) {
    return new ResponseEntity<>(
        new ApiResponse<>(false, error.getCode(), error.getMessage(), null, null),
        error.getHttpStatus());
  }
}

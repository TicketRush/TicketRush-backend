package com.ticketrush.queue;

import com.ticketrush.dto.response.ApiResponse;
import com.ticketrush.exception.BusinessException;
import com.ticketrush.queue.dto.EnqueueResponse;
import com.ticketrush.queue.dto.WaitingStatusResponse;
import com.ticketrush.security.JwtTokenProvider;
import com.ticketrush.status.ErrorStatus;
import com.ticketrush.status.SuccessStatus;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * <p>그 대가로 게이트웨이가 주입하는 {@code X-User-Id} 헤더가 없다. 진입·개시에서만 JWT를 직접 파싱하고, 이후 폴링은 불투명 대기 토큰만 대조한다.
 */
@Tag(name = "대기열", description = "대기열 진입·상태 확인 API (게이트웨이 로컬 처리)")
@RestController
@RequestMapping("/api/v1/queue")
public class WaitingRoomController {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String WAITING_TOKEN_HEADER = "X-Waiting-Token";
  private static final String ROLE_ADMIN = "ADMIN";

  private final WaitingRoomService waitingRoomService;
  private final JwtTokenProvider jwtTokenProvider;

  public WaitingRoomController(
      WaitingRoomService waitingRoomService, JwtTokenProvider jwtTokenProvider) {
    this.waitingRoomService = waitingRoomService;
    this.jwtTokenProvider = jwtTokenProvider;
  }

  @Operation(
      summary = "대기열 개시 (ADMIN)",
      description =
          "승급 임계치의 기준점을 심는다. 이 시각을 진입의 부작용으로 두면 오픈 전에 미리 진입해 둔 사람이 임계치를 부풀려 대기열을 무력화할 수 있다. "
              + "이미 열려 있으면 덮어쓰지 않는다.")
  @PostMapping("/{performanceId}/open")
  public Mono<ResponseEntity<ApiResponse<?>>> open(
      @PathVariable Long performanceId,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

    return Mono.fromCallable(() -> requireAdmin(authorization))
        .flatMap(ignored -> waitingRoomService.open(performanceId))
        .map(openedAt -> ok(SuccessStatus.OK, openedAt))
        .onErrorResume(BusinessException.class, e -> Mono.just(fail(e.getErrorStatus())));
  }

  @Operation(
      summary = "대기열 진입",
      description = "순번을 발급하고 불투명 대기 토큰을 돌려준다. 1인 1회라 여기서만 JWT 서명을 검증한다. 재진입해도 최초 순번과 토큰이 유지된다.")
  @PostMapping("/{performanceId}/enqueue")
  public Mono<ResponseEntity<ApiResponse<EnqueueResponse>>> enqueue(
      @PathVariable Long performanceId,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

    return Mono.fromCallable(() -> resolveUserId(authorization))
        .flatMap(userId -> waitingRoomService.enqueue(performanceId, userId))
        .map(result -> ApiResponse.onSuccess(SuccessStatus.CREATED, result))
        .onErrorResume(BusinessException.class, e -> Mono.just(failTyped(e.getErrorStatus())));
  }

  /**
   * 대기열 상태 확인 — 순번·대기 인원·다음 폴링 시각, 허용선 안이면 입장 토큰.
   *
   * <p>1만 명이 폴링하는 유일한 경로다. 여기에 무언가를 더하기 전에 ADR §4를 다시 읽는다.
   */
  @Operation(
      summary = "대기열 상태 확인",
      description =
          "JWT가 아니라 진입 시 받은 대기 토큰(X-Waiting-Token)을 대조한다. 응답의 next_poll_after_seconds 만큼 쉬었다가 "
              + "다시 호출한다(클라이언트는 여기에 지터를 더한다). 허용선 안이면 entry_token 이 함께 온다.")
  @GetMapping("/{performanceId}/status")
  public Mono<ResponseEntity<ApiResponse<WaitingStatusResponse>>> status(
      @PathVariable Long performanceId,
      @RequestHeader(value = WAITING_TOKEN_HEADER, required = false) String waitingToken) {

    return waitingRoomService
        .status(performanceId, waitingToken)
        .map(result -> ApiResponse.onSuccess(SuccessStatus.OK, result))
        .onErrorResume(BusinessException.class, e -> Mono.just(failTyped(e.getErrorStatus())));
  }

  /**
   * 서명 검증과 클레임 추출을 한 번에 한다.
   *
   * <p>{@code validateToken} / {@code getType} / {@code getUserId} 를 따로 부르면 {@code
   * parseSignedClaims} 가 요청당 네 번 돈다. CPU가 이미 벽인 곳에서(ADR 0009 §맥락) 1만 명이 램프 구간에 몰리는 유일한 서명 경로다.
   */
  private Claims parseAccessToken(String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_TOKEN);
    }
    Claims claims;
    try {
      claims = jwtTokenProvider.getClaims(authorization.substring(BEARER_PREFIX.length()));
    } catch (RuntimeException e) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_TOKEN);
    }
    if (!"access".equals(claims.get("type", String.class))) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_TOKEN_TYPE);
    }
    return claims;
  }

  private Long resolveUserId(String authorization) {
    return Long.valueOf(parseAccessToken(authorization).getSubject());
  }

  private Long requireAdmin(String authorization) {
    Claims claims = parseAccessToken(authorization);
    if (!ROLE_ADMIN.equals(claims.get("role", String.class))) {
      throw new BusinessException(ErrorStatus.AUTH_ACCESS_DENIED);
    }
    return Long.valueOf(claims.getSubject());
  }

  // ApiResponse.onFailure 는 ResponseEntity<ApiResponse<?>> 를 돌려주는데 성공 경로는
  // ResponseEntity<ApiResponse<T>> 라 Mono 타입이 갈린다. 실패 쪽을 T로 좁히는 얇은 어댑터를 둔다
  // (게이트웨이에 BusinessException 핸들러가 없는 문제와 같은 뿌리 — 별건 이슈).
  @SuppressWarnings("unchecked")
  private static <T> ResponseEntity<ApiResponse<T>> failTyped(ErrorStatus error) {
    return (ResponseEntity<ApiResponse<T>>) (ResponseEntity<?>) ApiResponse.onFailure(error);
  }

  private static ResponseEntity<ApiResponse<?>> ok(SuccessStatus status, Object result) {
    return new ResponseEntity<>(
        new ApiResponse<>(true, status.getCode(), status.getMessage(), null, result),
        status.getHttpStatus());
  }

  private static ResponseEntity<ApiResponse<?>> fail(ErrorStatus error) {
    return ApiResponse.onFailure(error);
  }
}

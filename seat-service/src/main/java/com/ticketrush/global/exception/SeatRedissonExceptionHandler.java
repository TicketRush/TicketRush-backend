package com.ticketrush.global.exception;

import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.ErrorStatus;
import lombok.extern.slf4j.Slf4j;
import org.redisson.client.RedisException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Redisson 장애를 500이 아니라 503 + 재시도 안내로 내보낸다(ADR 0008).
 *
 * <p>common의 {@code GlobalExceptionHandler}가 아니라 여기 있는 이유: Redisson 의존성은 seat-service에만 있다. common에
 * {@code @ExceptionHandler(RedisException.class)}를 두면 Redisson이 없는 나머지 서비스가 어드바이스를 로딩할 때 클래스를 찾지 못해
 * 기동에 실패한다.
 *
 * <p>Redisson은 Spring의 예외 변환을 타지 않아 {@code DataAccessException}이 아닌 {@link RedisException}을 그대로
 * 던진다. 그래서 common의 Lettuce용 핸들러에 걸리지 않고, 이것이 없으면 {@code Exception} 핸들러의 500 "관리자에게 문의"로 샌다.
 *
 * <p>{@code @Order}로 common의 어드바이스보다 먼저 평가되게 한다. 양쪽이 겹치는 예외는 없지만, 순서를 명시하지 않으면 등록 순서가 클래스패스 스캔에 의존해
 * 재현되지 않는다.
 *
 * <p>좌석 락의 Kafka 경로(BookingCreatedEventListener)는 HTTP가 아니라 이 어드바이스를 타지 않는다 — {@code
 * KafkaConsumerErrorPolicy}가 Redis 예외를 일시로 분류해 재시도·DLT로 보존한다. 이 어드바이스가 실제로 담당하는 것은 {@code
 * SeatInternalController}의 확정(confirmSold) 같은 HTTP 진입점이다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SeatRedissonExceptionHandler {

  @ExceptionHandler(RedisException.class)
  public ResponseEntity<ApiResponse<?>> handleRedissonException(RedisException e) {
    log.error("Redisson 장애로 요청을 거절했습니다.", e);

    return ApiResponse.onFailure(ErrorStatus.INFRA_TRANSIENT_UNAVAILABLE);
  }
}

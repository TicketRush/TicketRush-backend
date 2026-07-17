package com.ticketrush.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.ErrorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Redisson 장애가 503으로 나가는지 검증한다(ADR 0008).
 *
 * <p>이 테스트의 존재 이유는 "Redisson 예외가 common의 핸들러에 걸리지 않는다"는 사실이다. Redisson은 Spring 예외 변환을 타지 않아
 * DataAccessException이 아니므로, 이 어드바이스가 없으면 좌석 확정 API가 조용히 500으로 샌다.
 */
class SeatRedissonExceptionHandlerTest {

  private final SeatRedissonExceptionHandler handler = new SeatRedissonExceptionHandler();

  @Test
  @DisplayName("Redisson 연결 실패는 503 INFRA_503_001로 나간다")
  void redissonConnectionFailureReturns503() {
    ResponseEntity<ApiResponse<?>> response =
        handler.handleRedissonException(new RedisConnectionException("connection refused"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().getCode())
        .isEqualTo(ErrorStatus.INFRA_TRANSIENT_UNAVAILABLE.getCode());
  }

  @Test
  @DisplayName("Redisson 타임아웃(hang형)도 같은 503으로 나간다")
  void redissonTimeoutReturns503() {
    ResponseEntity<ApiResponse<?>> response =
        handler.handleRedissonException(new RedisTimeoutException("command timed out"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  @DisplayName("Redisson 예외는 Spring의 DataAccessException이 아니다 — common 핸들러가 못 잡는 근거")
  void redissonExceptionIsNotSpringDataAccessException() {
    assertThat(new RedisConnectionException("x"))
        .isNotInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}

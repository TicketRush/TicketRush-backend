package com.ticketrush.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.ErrorStatus;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Redis 장애가 500 "관리자에게 문의"로 뭉개지지 않고 503 + 재시도 안내로 나가는지 검증한다(ADR 0008).
 *
 * <p>MockMvc를 쓰지 않는 이유: common은 Jackson 3(tools.jackson)를 쓰는데 MockMvc의 기본 메시지 컨버터가 Jackson 2를 찾아
 * 클래스패스에서 깨진다. 대신 핸들러의 반환값과 {@code @ExceptionHandler} 등록 예외 타입을 함께 검증해, "어떤 예외가 이 핸들러로 라우팅되는가"와 "그때
 * 무엇을 반환하는가"를 모두 덮는다.
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  @DisplayName("Redis 연결 실패는 503 INFRA_503_001로 나간다")
  void redisConnectionFailureReturns503() {
    ResponseEntity<ApiResponse<?>> response =
        handler.handleTransientInfraException(new RedisConnectionFailureException("refused"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().getCode())
        .isEqualTo(ErrorStatus.INFRA_TRANSIENT_UNAVAILABLE.getCode());
  }

  @Test
  @DisplayName("Lettuce 예외만 등록한다 — Redisson 예외는 seat-service의 어드바이스가 맡는다")
  void onlyLettuceExceptionIsRoutedHere() throws NoSuchMethodException {
    ExceptionHandler annotation =
        GlobalExceptionHandler.class
            .getMethod("handleTransientInfraException", RuntimeException.class)
            .getAnnotation(ExceptionHandler.class);

    List<Class<? extends Throwable>> registered = Arrays.asList(annotation.value());

    assertThat(registered).containsExactly(RedisConnectionFailureException.class);
  }

  @Test
  @DisplayName("QueryTimeoutException은 잡지 않는다 — 잡으면 전 서비스의 DB 타임아웃까지 503이 된다")
  void queryTimeoutIsNotRoutedHere() throws NoSuchMethodException {
    ExceptionHandler annotation =
        GlobalExceptionHandler.class
            .getMethod("handleTransientInfraException", RuntimeException.class)
            .getAnnotation(ExceptionHandler.class);

    assertThat(Arrays.asList(annotation.value())).doesNotContain(QueryTimeoutException.class);
  }

  @Test
  @DisplayName("일시 장애가 아닌 예외는 종전대로 500이다 — 503으로 넓게 삼키지 않는다")
  void otherExceptionStillReturns500() {
    ResponseEntity<ApiResponse<?>> response =
        handler.handleException(new IllegalStateException("boom"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().getCode()).isEqualTo(ErrorStatus.INTERNAL_SERVER_ERROR.getCode());
  }

  /*
   * #636 — 업로드 크기 초과가 500으로 나가던 것을 413으로 바로잡는다.
   *
   * 핸들러가 없으면 catch-all(Exception)로 떨어져 INTERNAL_SERVER_ERROR가 나갔다. 클라이언트 입장에서는
   * 자기가 고칠 수 있는 실수인데 서버 장애로 보이고, 용량 문제라는 것을 코드로 구분할 수 없었다.
   */
  @Test
  @DisplayName("업로드 크기 초과는 413 FILE_413_001로 나간다")
  void maxUploadSizeExceededReturns413() {
    ResponseEntity<ApiResponse<?>> response =
        handler.handleMaxUploadSizeExceededException(new MaxUploadSizeExceededException(10485760L));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    assertThat(response.getBody().getCode()).isEqualTo(ErrorStatus.FILE_SIZE_EXCEEDED.getCode());
  }

  @Test
  @DisplayName("크기 초과 예외가 전용 핸들러로 라우팅된다 — catch-all의 500으로 새지 않는다")
  void maxUploadSizeExceededIsRoutedToDedicatedHandler() throws NoSuchMethodException {
    ExceptionHandler annotation =
        GlobalExceptionHandler.class
            .getMethod("handleMaxUploadSizeExceededException", MaxUploadSizeExceededException.class)
            .getAnnotation(ExceptionHandler.class);

    assertThat(Arrays.asList(annotation.value()))
        .containsExactly(MaxUploadSizeExceededException.class);
  }
}

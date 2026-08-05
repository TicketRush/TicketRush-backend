package com.ticketrush.global.exception;

import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.ErrorStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.nio.file.AccessDeniedException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final String EXCEPTION_ATTRIBUTE = "handledException";

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<?>> validation(ConstraintViolationException e) {
    storeException(e);

    String errorMessage =
        e.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
            .collect(Collectors.joining(", "));

    log.warn("Constraint validation failed: {}", errorMessage);

    return ApiResponse.onFailure(ErrorStatus.VALIDATION_ERROR, errorMessage);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<?>> handleMethodArgumentNotValidException(
      MethodArgumentNotValidException e) {
    storeException(e);

    String errorMessage =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining(", "));

    log.warn("Validation failed: {}", errorMessage);

    return ApiResponse.onFailure(ErrorStatus.VALIDATION_ERROR, errorMessage);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<?>> handleNoResourceFoundException(NoResourceFoundException e) {
    storeException(e);

    return ApiResponse.onFailure(ErrorStatus.NOT_FOUND);
  }

  /*
   * @Version 낙관적 락 충돌은 커밋 시점에 던져져 UseCase에서 잡을 수 없다 (#397).
   * 동시 수정이 겹친 정상 상황이므로 500이 아니라 409로 변환한다. 늦은 쪽 요청은 재시도하면 된다.
   */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ApiResponse<?>> handleOptimisticLockingFailureException(
      OptimisticLockingFailureException e) {
    storeException(e);
    log.warn("Optimistic locking conflict: {}", e.getMessage());

    return ApiResponse.onFailure(ErrorStatus.CONFLICT);
  }

  /*
   * Redis 불가 = fail-closed로 요청을 거절한다(ADR 0008). 재시도하면 되는 일시 장애인데
   * 아래 Exception 핸들러에 걸려 500 "관리자에게 문의"로 나가면 사용자를 오도한다.
   *
   * 여기서 잡는 것은 Lettuce 계열(RedisTemplate·캐시)뿐이다. Spring이 RedisConnectionFailureException으로
   * 변환해주며, hang형(연결은 살아있고 응답만 없음)은 RedisCommandTimeoutException이 원인으로 감싸여 온다.
   *
   * Redisson(좌석 락)은 Spring 예외 변환을 타지 않아 RedisException(RuntimeException)을 그대로 던지므로
   * 이 핸들러에 걸리지 않는다. 그러나 Redisson 의존성은 seat-service에만 있어 common에서 등록하면
   * 나머지 서비스가 클래스 로딩에 실패한다 — 그래서 seat-service의 SeatRedissonExceptionHandler가 맡는다.
   *
   * QueryTimeoutException은 일부러 잡지 않는다. Redis 전용이 아니라 JDBC·JPA 쿼리 타임아웃도
   * 여기로 변환되므로, 등록하면 common을 쓰는 전 서비스의 DB 타임아웃 응답까지 조용히 503으로 바뀐다.
   * DB 타임아웃의 처리 방침은 ADR 0008의 범위가 아니다.
   *
   * NOAUTH(비밀번호 오설정)도 잡지 않는다. RedisSystemException으로 도착해 500이 되는데,
   * 그건 일시 장애가 아니라 설정 버그이므로 500이 옳다.
   *
   * 좌석 락의 Kafka 경로는 이 핸들러를 타지 않는다 — KafkaConsumerErrorPolicy가 Redis 예외를
   * 일시로 분류해 재시도·DLT로 보존한다.
   */
  @ExceptionHandler(RedisConnectionFailureException.class)
  public ResponseEntity<ApiResponse<?>> handleTransientInfraException(RuntimeException e) {
    storeException(e);
    log.error("인프라 일시 장애로 요청을 거절했습니다.", e);

    return ApiResponse.onFailure(ErrorStatus.INFRA_TRANSIENT_UNAVAILABLE);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
    storeException(e);
    log.error("Unhandled exception occurred", e);
    return ApiResponse.onFailure((ErrorStatus.INTERNAL_SERVER_ERROR));
  }

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<?>> handleGeneralException(BusinessException e) {
    log.warn("Business exception: {}", e.getMessage());
    storeException(e);

    if (e.getData() != null) {
      return ApiResponse.onFailure(e.getErrorStatus(), e.getData());
    }

    return ApiResponse.onFailure(e.getErrorStatus(), e.getMessage());
  }

  /*
   * 요청이 스펙과 맞지 않아 컨트롤러에 들어가지도 못한 경우다. 전부 클라이언트가 고칠 수 있는 실수이므로 400으로 답한다.
   *
   * MissingServletRequestParameterException(필수 쿼리 파라미터 누락)이 여기 없으면 catch-all(Exception)로 떨어져
   * 500이 나간다 — 클라이언트 실수가 서버 장애로 보이고, 호출자는 재시도해도 소용없다는 것을 알 수 없다(#562).
   */
  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    ConversionFailedException.class,
    MissingServletRequestParameterException.class
  })
  public ResponseEntity<ApiResponse<?>> handleConversionFailedException(Exception e) {
    storeException(e);

    return ApiResponse.onFailure((ErrorStatus.BAD_REQUEST));
  }

  @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
  public ResponseEntity<ApiResponse<?>> handleAccessDeniedException(Exception e) {

    log.warn("Access Denied: {}", e.getMessage());

    storeException(e);

    return ApiResponse.onFailure(ErrorStatus.AUTH_ACCESS_DENIED);
  }

  private void storeException(Exception e) {
    try {
      ServletRequestAttributes attributes =
          (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

      if (attributes != null) {
        HttpServletRequest request = attributes.getRequest();
        request.setAttribute(EXCEPTION_ATTRIBUTE, e);
      }
    } catch (Exception ex) {
      log.error("Failed to save exception in ExceptionAdvice", ex);
    }
  }
}

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
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

  @ExceptionHandler({MethodArgumentTypeMismatchException.class, ConversionFailedException.class})
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

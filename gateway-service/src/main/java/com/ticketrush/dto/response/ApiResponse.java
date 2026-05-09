package com.ticketrush.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ticketrush.status.ErrorStatus;
import com.ticketrush.status.SuccessStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.ResponseEntity;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({"isSuccess", "code", "message", "traceId", "pagination", "result"})
public class ApiResponse<T> {

  private Boolean isSuccess;
  private String code;
  private String message;
  private String traceId;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private T result;

  // 성공 - 기본 응답
  public static ResponseEntity<ApiResponse<Void>> onSuccess(SuccessStatus status) {
    return new ResponseEntity<>(
        new ApiResponse<>(true, status.getCode(), status.getMessage(), null, null),
        status.getHttpStatus());
  }

  // 성공 - 데이터 포함
  public static <T> ResponseEntity<ApiResponse<T>> onSuccess(SuccessStatus status, T result) {
    return new ResponseEntity<>(
        new ApiResponse<>(true, status.getCode(), status.getMessage(), null, result),
        status.getHttpStatus());
  }

  // 실패 - 기본 응답
  public static ResponseEntity<ApiResponse<?>> onFailure(ErrorStatus error) {
    return new ResponseEntity<>(
        new ApiResponse<>(false, error.getCode(), error.getMessage(), null, null),
        error.getHttpStatus());
  }

  // 실패 - 커스텀 메시지 포함
  public static ResponseEntity<ApiResponse<?>> onFailure(ErrorStatus error, String message) {
    return new ResponseEntity<>(
        new ApiResponse<>(false, error.getCode(), error.getMessage(message), null, null),
        error.getHttpStatus());
  }

  // 실패 - 데이터 포함
  public static ResponseEntity<ApiResponse<?>> onFailure(ErrorStatus error, Object data) {
    return new ResponseEntity<>(
        new ApiResponse<>(false, error.getCode(), error.getMessage(), null, data),
        error.getHttpStatus());
  }
}

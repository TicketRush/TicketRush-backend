package com.ticketrush.global.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ticketrush.global.event.EventUtils;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.status.SuccessStatus;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import lombok.Getter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.http.ResponseEntity;

@Getter
@JsonPropertyOrder({"isSuccess", "code", "message", "traceId", "paginationInfo", "result"})
public class ApiResponse<T> {

  private final Boolean isSuccess;
  private final String code;
  private final String message;
  private final String traceId;

  // 페이지네이션 필드 (null일 경우 JSON에서 제외)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final PaginationInfo paginationInfo;

  // 실제 데이터 (null일 경우 JSON에서 제외)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final T result;

  // 내부 생성자
  private ApiResponse(
      Boolean isSuccess, String code, String message, PaginationInfo paginationInfo, T result) {
    this.isSuccess = isSuccess;
    this.code = code;
    this.message = message;
    this.traceId = EventUtils.extractTraceId();
    this.paginationInfo = paginationInfo;
    this.result = result;
  }

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

  // 성공 - 오프셋 기반(게시판) 페이징 포함
  public static <T> ResponseEntity<ApiResponse<List<T>>> onSuccess(
      SuccessStatus status, Page<T> page) {

    // PaginationInfo 타입으로 할당
    PaginationInfo pageInfo =
        new PageInfo(
            page.getNumber(),
            page.getSize(),
            page.hasNext(),
            page.getTotalElements(),
            page.getTotalPages());

    return new ResponseEntity<>(
        new ApiResponse<>(true, status.getCode(), status.getMessage(), pageInfo, page.getContent()),
        status.getHttpStatus());
  }

  // 성공 - 커서 기반(무한 스크롤) 페이징 포함
  public static <T> ResponseEntity<ApiResponse<List<T>>> onSuccess(
      SuccessStatus status, Slice<T> slice, Function<T, Long> cursorExtractor) {

    Objects.requireNonNull(cursorExtractor, "cursorExtractor must not be null");

    Long nextCursor = null;
    List<T> content = slice.getContent();

    if (slice.hasNext() && !content.isEmpty()) {
      nextCursor = cursorExtractor.apply(content.getLast());
    }

    // PaginationInfo 타입으로 할당
    PaginationInfo cursorInfo = new CursorInfo(slice.hasNext(), nextCursor, slice.getSize());

    return new ResponseEntity<>(
        new ApiResponse<>(true, status.getCode(), status.getMessage(), cursorInfo, content),
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

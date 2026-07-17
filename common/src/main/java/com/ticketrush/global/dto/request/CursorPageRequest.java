package com.ticketrush.global.dto.request;

import static com.ticketrush.global.constants.PaginationConstants.DEFAULT_PAGE_SIZE;
import static com.ticketrush.global.constants.PaginationConstants.MAX_PAGE_SIZE;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "커서 기반 페이징 요청 (무한 스크롤)")
public record CursorPageRequest(
    @Schema(description = "이전 응답의 nextCursor 값 (첫 페이지는 생략, 1 미만 값은 첫 페이지로 취급)", example = "42")
        Long cursorId,
    @Schema(description = "페이지 크기 (기본 10, 최대 50 — 초과 시 50으로 보정)", example = "10") Integer size)
    implements PaginationRequest {

  // 컴팩트 생성자를 통한 기본값 설정
  public CursorPageRequest {
    // 유효하지 않은 커서는 첫 페이지 요청으로 취급
    if (cursorId != null && cursorId < 1) {
      cursorId = null;
    }
    if (size == null || size < 1) {
      size = DEFAULT_PAGE_SIZE;
    }
    if (size > MAX_PAGE_SIZE) {
      size = MAX_PAGE_SIZE;
    }
  }
}

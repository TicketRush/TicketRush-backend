package com.ticketrush.global.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.constants.PaginationConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CursorPageRequestTest {

  @Test
  @DisplayName("size가 비어있거나 범위를 벗어나면 공통 기본값으로 보정한다")
  void cursor_page_request_normalizes_invalid_size() {
    CursorPageRequest request = new CursorPageRequest(1L, 0);

    assertThat(request.cursorId()).isEqualTo(1L);
    assertThat(request.size()).isEqualTo(PaginationConstants.DEFAULT_PAGE_SIZE);
  }

  @Test
  @DisplayName("size가 공통 최대 크기를 초과하면 최대 크기로 보정한다")
  void cursor_page_request_caps_size() {
    CursorPageRequest request = new CursorPageRequest(1L, 1000);

    assertThat(request.size()).isEqualTo(PaginationConstants.MAX_PAGE_SIZE);
  }

  @Test
  @DisplayName("유효하지 않은 cursorId는 첫 페이지 요청(null)으로 보정한다")
  void cursor_page_request_normalizes_invalid_cursor_id() {
    CursorPageRequest request = new CursorPageRequest(0L, 10);

    assertThat(request.cursorId()).isNull();
    assertThat(request.size()).isEqualTo(10);
  }

  @Test
  @DisplayName("cursorId가 없으면 첫 페이지 요청으로 유지한다")
  void cursor_page_request_allows_null_cursor_id() {
    CursorPageRequest request = new CursorPageRequest(null, 10);

    assertThat(request.cursorId()).isNull();
    assertThat(request.size()).isEqualTo(10);
  }
}

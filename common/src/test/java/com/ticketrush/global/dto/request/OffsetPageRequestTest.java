package com.ticketrush.global.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.constants.PaginationConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OffsetPageRequestTest {

  @Test
  @DisplayName("page와 size가 비어있거나 범위를 벗어나면 공통 기본값으로 보정한다")
  void offset_page_request_normalizes_invalid_values() {
    OffsetPageRequest request = new OffsetPageRequest(-1, 0);

    assertThat(request.page()).isZero();
    assertThat(request.size()).isEqualTo(PaginationConstants.DEFAULT_PAGE_SIZE);
  }

  @Test
  @DisplayName("size가 공통 최대 크기를 초과하면 최대 크기로 보정한다")
  void offset_page_request_caps_size() {
    OffsetPageRequest request = new OffsetPageRequest(1, 1000);

    assertThat(request.page()).isEqualTo(1);
    assertThat(request.size()).isEqualTo(PaginationConstants.MAX_PAGE_SIZE);
  }
}

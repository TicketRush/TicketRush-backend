package com.ticketrush.global.dto.request;

import static com.ticketrush.global.constants.PaginationConstants.DEFAULT_PAGE_SIZE;
import static com.ticketrush.global.constants.PaginationConstants.MAX_PAGE_SIZE;

public record OffsetPageRequest(Integer page, Integer size) implements PaginationRequest {

  public OffsetPageRequest {
    if (page == null || page < 0) {
      page = 0;
    }
    if (size == null || size < 1) {
      size = DEFAULT_PAGE_SIZE;
    }
    if (size > MAX_PAGE_SIZE) {
      size = MAX_PAGE_SIZE;
    }
  }
}

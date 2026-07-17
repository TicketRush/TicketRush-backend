package com.ticketrush.global.dto.request;

/** 모든 페이징 요청 DTO가 구현해야 하는 공통 인터페이스 */
public interface PaginationRequest {
  Integer size();
}

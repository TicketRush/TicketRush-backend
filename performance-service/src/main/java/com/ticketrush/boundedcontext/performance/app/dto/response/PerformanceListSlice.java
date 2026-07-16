package com.ticketrush.boundedcontext.performance.app.dto.response;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

/**
 * 공연 목록 조회 결과의 Redis 캐시 값. {@link SliceImpl}은 Jackson 역직렬화가 불가능해 캐시에 저장 가능한 형태(content + hasNext)로
 * 평탄화한다.
 */
public record PerformanceListSlice(List<PerformanceListResponse> content, boolean hasNext) {

  public static PerformanceListSlice from(Slice<PerformanceListResponse> slice) {
    return new PerformanceListSlice(slice.getContent(), slice.hasNext());
  }

  public Slice<PerformanceListResponse> toSlice(int size) {
    return new SliceImpl<>(content, PageRequest.of(0, size), hasNext);
  }
}

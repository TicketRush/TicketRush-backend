package com.ticketrush.boundedcontext.performance.app.dto.response;

import com.ticketrush.boundedcontext.performance.domain.entity.Performance;

public record PerformanceCreateResponse(Long performanceId) {

  /**
   * Entity를 Response DTO로 변환하는 정적 팩토리 메서드
   *
   * @param performance 저장된 공연 엔티티
   * @return 생성된 응답 DTO
   */
  public static PerformanceCreateResponse from(Performance performance) {
    return new PerformanceCreateResponse(performance.getId());
  }
}

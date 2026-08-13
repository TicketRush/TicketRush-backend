package com.ticketrush.boundedcontext.performance.app.dto.response;

import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;

/**
 * 대시보드 집계에 필요한 공연 최소 정보 (#563). 내부 산출물이며 API로 노출되지 않는다.
 *
 * <p>엔티티 대신 이 프로젝션을 쓰는 이유는 대시보드가 전 공연을 훑기 때문이다. 장르별 매출은 공연을 장르로 접어야 하고 평균 점유율은 상태로 모수를 골라야 하는데,
 * 그러자고 이미지 URL·설명 같은 큰 컬럼과 {@code @ElementCollection} 두 개까지 끌고 올 이유가 없다.
 */
public record PerformanceAggregateRow(Long id, Genre genre, PerformanceStatus status) {}

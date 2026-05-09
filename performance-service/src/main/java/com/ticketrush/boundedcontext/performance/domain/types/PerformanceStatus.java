package com.ticketrush.boundedcontext.performance.domain.types;

import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PerformanceStatus {
  UPCOMING("판매 예정", Set.of("ON_SALE", "CANCELED")),
  ON_SALE("판매 중", Set.of("CLOSED", "CANCELED")),
  CLOSED("판매 종료", Set.of("CANCELED")),
  CANCELED("취소", Set.of());

  private final String description;
  private final Set<String> allowedTransitions;

  public boolean canTransitionTo(PerformanceStatus target) {
    return allowedTransitions.contains(target.name());
  }
}

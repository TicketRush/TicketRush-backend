package com.ticketrush.global.types;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 공연의 판매 상태. 소유는 performance-service 지만 <b>서비스를 가로질러 판정에 쓰이므로</b> common 에 둔다 (#671).
 *
 * <p>booking 이 예매 생성 시 오픈 여부를 판정할 때 이 값을 읽는다({@code BookingValidateReferencesUseCase}). 문자열 리터럴로
 * 비교하면 상태가 늘거나 이름이 바뀔 때 한쪽만 따라가므로, {@code SeatStatus} 가 같은 이유로 여기 있는 것과 같은 자리에 둔다.
 *
 * <p>{@link #canTransitionTo} 의 전이표는 performance-service 만 쓴다 — 상태를 바꾸는 주체가 그쪽뿐이다.
 */
@Getter
@AllArgsConstructor
public enum PerformanceStatus {
  UPCOMING("판매 예정"),
  ON_SALE("판매 중"),
  CLOSED("판매 종료"),
  CANCELED("취소");

  private final String description;

  public boolean canTransitionTo(PerformanceStatus target) {
    return switch (this) {
      case UPCOMING -> target == ON_SALE || target == CANCELED;
      case ON_SALE -> target == CLOSED || target == CANCELED;
      case CLOSED -> target == CANCELED;
      case CANCELED -> false;
    };
  }
}

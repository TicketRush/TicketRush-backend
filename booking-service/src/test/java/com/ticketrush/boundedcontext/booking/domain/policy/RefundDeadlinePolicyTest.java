package com.ticketrush.boundedcontext.booking.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-7 경계와 시간대 해석을 고정한다 (#668).
 *
 * <p>Clock은 운영과 같은 UTC로 고정한다({@code ClockConfig}). JVM 기본 시간대에 기대면 KST 로컬에서는 통과하고 UTC 컨테이너에서만 9시간
 * 어긋나는 회귀를 잡지 못한다.
 */
class RefundDeadlinePolicyTest {

  private static final LocalDate SHOW_DATE = LocalDate.of(2026, 7, 20);
  private static final LocalTime SHOW_TIME = LocalTime.of(19, 30);

  /** 공연 2026-07-20 19:30 KST = 2026-07-20 10:30 UTC. 그 7일 전은 2026-07-13 10:30 UTC. */
  private static final Instant EXACTLY_SEVEN_DAYS_BEFORE = Instant.parse("2026-07-13T10:30:00Z");

  private RefundDeadlinePolicy policyAt(Instant now) {
    return new RefundDeadlinePolicy(Clock.fixed(now, ZoneId.of("UTC")));
  }

  @Test
  @DisplayName("성공: 공연까지 정확히 7일 남은 경계는 허용한다 (#668)")
  void allowsRefund_atExactBoundary() {
    // given
    RefundDeadlinePolicy policy = policyAt(EXACTLY_SEVEN_DAYS_BEFORE);

    // when
    boolean allowed = policy.allowsRefund(SHOW_DATE, SHOW_TIME);

    // then
    // 프론트 isRefundableBooking 이 `>= 7`로 판정한다. `>`로 바꾸면 경계에서 버튼은 활성인데 API는 409가 된다.
    assertThat(allowed).isTrue();
  }

  @Test
  @DisplayName("실패: 7일에서 1초만 모자라도 거절한다 (#668)")
  void allowsRefund_rejectsOneSecondPastBoundary() {
    // given
    RefundDeadlinePolicy policy = policyAt(EXACTLY_SEVEN_DAYS_BEFORE.plusSeconds(1));

    // when
    boolean allowed = policy.allowsRefund(SHOW_DATE, SHOW_TIME);

    // then
    assertThat(allowed).isFalse();
  }

  @Test
  @DisplayName("성공: 7일보다 여유가 있으면 허용한다 (#668)")
  void allowsRefund_wellBeforeDeadline() {
    // given
    RefundDeadlinePolicy policy = policyAt(EXACTLY_SEVEN_DAYS_BEFORE.minusSeconds(1));

    // when & then
    assertThat(policy.allowsRefund(SHOW_DATE, SHOW_TIME)).isTrue();
  }

  @Test
  @DisplayName("실패: 공연 당일은 거절한다 (#668)")
  void allowsRefund_rejectsOnShowDay() {
    // given
    RefundDeadlinePolicy policy = policyAt(Instant.parse("2026-07-20T00:00:00Z"));

    // when & then
    assertThat(policy.allowsRefund(SHOW_DATE, SHOW_TIME)).isFalse();
  }

  @Test
  @DisplayName("실패: 공연이 이미 지났으면 거절한다 (#668)")
  void allowsRefund_rejectsAfterShow() {
    // given — 공연 하루 뒤. Duration이 음수가 되는 분기를 고정한다.
    RefundDeadlinePolicy policy = policyAt(Instant.parse("2026-07-21T10:30:00Z"));

    // when & then
    assertThat(policy.allowsRefund(SHOW_DATE, SHOW_TIME)).isFalse();
  }

  @Test
  @DisplayName("성공: 공연 일시를 UTC가 아니라 Asia/Seoul 벽시계로 해석한다 (#668)")
  void allowsRefund_interpretsShowTimeAsSeoulWallClock() {
    // given — 같은 날짜·시각을 UTC로 읽으면 경계가 9시간 뒤로 밀려 두 케이스의 판정이 뒤집힌다.
    Instant justInside = Instant.parse("2026-07-13T10:29:59Z"); // KST 19:29:59
    Instant justOutside = Instant.parse("2026-07-13T10:30:01Z"); // KST 19:30:01

    // when & then
    assertThat(policyAt(justInside).allowsRefund(SHOW_DATE, SHOW_TIME)).isTrue();
    assertThat(policyAt(justOutside).allowsRefund(SHOW_DATE, SHOW_TIME)).isFalse();
  }
}

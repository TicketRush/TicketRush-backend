package com.ticketrush.boundedcontext.performance.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공연 시작 시각의 시간대 해석 (#651).
 *
 * <p>Spring 없이 {@code Clock.fixed}만으로 검증한다. 주입되는 Clock은 common {@code ClockConfig}처럼 UTC이고, 정책이 그것을
 * Asia/Seoul로 바꿔 읽는지가 핵심이다.
 */
class PerformanceShowTimePolicyTest {

  private static PerformanceShowTimePolicy policyAt(String utcInstant) {
    return new PerformanceShowTimePolicy(Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC));
  }

  @Test
  @DisplayName("UTC Clock을 Asia/Seoul 벽시계로 바꿔 읽는다 — UTC 10:30은 KST 19:30이다")
  void cutoff_convertsUtcClockToSeoulWallClock() {
    ShowTimeCutoff cutoff = policyAt("2026-09-15T10:30:00Z").cutoff();

    assertThat(cutoff.date()).isEqualTo(LocalDate.of(2026, 9, 15));
    assertThat(cutoff.time()).isEqualTo(LocalTime.of(19, 30));
  }

  /** KST 해석의 결정적 증거. UTC로 읽으면 아직 15일 15:30이지만 KST로는 이미 16일이다. 이 케이스가 깨지면 자정 전후 공연이 하루 어긋난다. */
  @Test
  @DisplayName("UTC 기준 아직 오늘이어도 KST로 자정을 넘겼으면 날짜가 다음 날이다")
  void cutoff_rollsDateWhenSeoulPassesMidnight() {
    ShowTimeCutoff cutoff = policyAt("2026-09-15T15:30:00Z").cutoff();

    assertThat(cutoff.date()).isEqualTo(LocalDate.of(2026, 9, 16));
    assertThat(cutoff.time()).isEqualTo(LocalTime.of(0, 30));
  }

  @Test
  @DisplayName("Clock의 존이 UTC가 아니어도 같은 순간이면 같은 KST 값이다")
  void cutoff_isIndependentOfClockZone() {
    Instant instant = Instant.parse("2026-09-15T10:30:00Z");
    ShowTimeCutoff fromUtc =
        new PerformanceShowTimePolicy(Clock.fixed(instant, ZoneOffset.UTC)).cutoff();
    ShowTimeCutoff fromNewYork =
        new PerformanceShowTimePolicy(Clock.fixed(instant, ZoneId.of("America/New_York"))).cutoff();

    assertThat(fromNewYork).isEqualTo(fromUtc);
  }

  @Test
  @DisplayName("초 미만은 절삭한다 — show_time은 초 정밀도라 나노초가 섞이면 정각 판정이 흔들린다")
  void cutoff_truncatesToSeconds() {
    ShowTimeCutoff cutoff = policyAt("2026-09-15T10:00:00.987654321Z").cutoff();

    assertThat(cutoff.time()).isEqualTo(LocalTime.of(19, 0, 0));
    assertThat(cutoff.time().getNano()).isZero();
  }
}

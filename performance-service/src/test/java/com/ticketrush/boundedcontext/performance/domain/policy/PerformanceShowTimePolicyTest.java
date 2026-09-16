package com.ticketrush.boundedcontext.performance.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공연 시작 시각(#651)과 예매 오픈 시각(#653)의 시간대 해석.
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

  @Test
  @DisplayName("예매 오픈 판정 기준도 Asia/Seoul 벽시계다 — UTC 10:30은 KST 19:30이다 (#653)")
  void bookingOpenCutoff_convertsUtcClockToSeoulWallClock() {
    LocalDateTime cutoff = policyAt("2026-09-15T10:30:00Z").bookingOpenCutoff();

    assertThat(cutoff).isEqualTo(LocalDateTime.of(2026, 9, 15, 19, 30));
  }

  /** 운영 UTC 런타임에서 "KST 20:00 오픈"이 제때 열리는 근거. UTC 11:00에 KST 20:00 값을 도래로 판정해야 한다. */
  @Test
  @DisplayName("KST 20:00 오픈 시각은 UTC 11:00 순간에 이미 도래한 것으로 판정된다")
  void bookingOpenCutoff_seoulEveningIsDueAtUtcMorning() {
    LocalDateTime cutoff = policyAt("2026-09-15T11:00:00Z").bookingOpenCutoff();
    LocalDateTime bookingOpenAt = LocalDateTime.of(2026, 9, 15, 20, 0);

    assertThat(bookingOpenAt).isBeforeOrEqualTo(cutoff);
  }

  @Test
  @DisplayName("UTC 기준 아직 오늘이어도 KST로 자정을 넘겼으면 예매 오픈 판정 기준은 다음 날이다")
  void bookingOpenCutoff_rollsDateWhenSeoulPassesMidnight() {
    LocalDateTime cutoff = policyAt("2026-09-15T15:30:00Z").bookingOpenCutoff();

    assertThat(cutoff).isEqualTo(LocalDateTime.of(2026, 9, 16, 0, 30));
  }

  @Test
  @DisplayName("예매 오픈 판정 기준은 Clock의 존과 무관하다")
  void bookingOpenCutoff_isIndependentOfClockZone() {
    Instant instant = Instant.parse("2026-09-15T10:30:00Z");
    LocalDateTime fromUtc =
        new PerformanceShowTimePolicy(Clock.fixed(instant, ZoneOffset.UTC)).bookingOpenCutoff();
    LocalDateTime fromNewYork =
        new PerformanceShowTimePolicy(Clock.fixed(instant, ZoneId.of("America/New_York")))
            .bookingOpenCutoff();

    assertThat(fromNewYork).isEqualTo(fromUtc);
  }

  @Test
  @DisplayName("예매 오픈 판정 기준도 초 미만을 절삭한다")
  void bookingOpenCutoff_truncatesToSeconds() {
    LocalDateTime cutoff = policyAt("2026-09-15T10:00:00.987654321Z").bookingOpenCutoff();

    assertThat(cutoff).isEqualTo(LocalDateTime.of(2026, 9, 15, 19, 0, 0));
    assertThat(cutoff.getNano()).isZero();
  }

  @Test
  @DisplayName("같은 순간의 공연 시각 판정 기준과 예매 오픈 판정 기준은 같은 날짜·시각이다")
  void bookingOpenCutoff_matchesCutoffAtSameInstant() {
    PerformanceShowTimePolicy policy = policyAt("2026-09-15T15:30:00.123Z");

    ShowTimeCutoff showTimeCutoff = policy.cutoff();
    LocalDateTime bookingOpenCutoff = policy.bookingOpenCutoff();

    assertThat(bookingOpenCutoff.toLocalDate()).isEqualTo(showTimeCutoff.date());
    assertThat(bookingOpenCutoff.toLocalTime()).isEqualTo(showTimeCutoff.time());
  }
}

package com.ticketrush.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 대기열 판정 로직(ADR 0009 §3).
 *
 * <p>여기서 틀리면 사용자가 영영 입장하지 못하거나(임계치가 안 오름) 대기열이 무의미해진다(전원 즉시 통과). 순수 함수라 Redis·Spring 없이 전부 고정할 수
 * 있고, 그래서 대기열 테스트 무게의 대부분이 이 파일에 있다.
 */
class WaitingRoomPolicyTest {

  @Nested
  @DisplayName("입장 허용 누적 인원")
  class AdmittedThreshold {

    @Test
    @DisplayName("경과 시간 × 초당 허용량으로 선형 증가한다")
    void 선형_증가() {
      long openedAt = 1_000_000L;

      assertThat(WaitingRoomPolicy.admittedThreshold(openedAt + 1_000L, openedAt, 20))
          .isEqualTo(20L);
      assertThat(WaitingRoomPolicy.admittedThreshold(openedAt + 10_000L, openedAt, 20))
          .isEqualTo(200L);
    }

    @Test
    @DisplayName("초 미만 경과도 반영한다 — 초 단위로 먼저 나누면 같은 초의 요청이 전부 같은 판정을 받는다")
    void 밀리초_정밀도() {
      long openedAt = 1_000_000L;

      assertThat(WaitingRoomPolicy.admittedThreshold(openedAt + 500L, openedAt, 20)).isEqualTo(10L);
    }

    @Test
    @DisplayName("개시 전이거나 시계가 뒤로 간 경우 0이다")
    void 경과_없음() {
      long openedAt = 1_000_000L;

      assertThat(WaitingRoomPolicy.admittedThreshold(openedAt, openedAt, 20)).isZero();
      assertThat(WaitingRoomPolicy.admittedThreshold(openedAt - 5_000L, openedAt, 20)).isZero();
    }

    @Test
    @DisplayName("허용량이 0 이하면 아무도 들이지 않는다")
    void 허용량_없음() {
      assertThat(WaitingRoomPolicy.admittedThreshold(2_000L, 1_000L, 0)).isZero();
    }

    @Test
    @DisplayName("대기열 TTL(6h) 만큼 경과해도 오버플로하지 않는다")
    void 오버플로_없음() {
      long sixHoursMs = 6L * 60 * 60 * 1000;

      assertThat(WaitingRoomPolicy.admittedThreshold(sixHoursMs, 0L, 20))
          .isEqualTo(sixHoursMs * 20 / 1000);
    }
  }

  @Nested
  @DisplayName("입장 판정")
  class Admitted {

    @Test
    @DisplayName("순번이 허용선보다 앞이면 들어간다 — 경계는 배타적이다")
    void 경계() {
      // rank는 0-based이므로 threshold=1이면 0번(선두) 한 명만 들어간다.
      assertThat(WaitingRoomPolicy.admitted(0L, 1L)).isTrue();
      assertThat(WaitingRoomPolicy.admitted(1L, 1L)).isFalse();
    }

    @Test
    @DisplayName("대기열에 없으면(rank 음수) 들이지 않는다")
    void 미등록() {
      assertThat(WaitingRoomPolicy.admitted(-1L, 1_000L)).isFalse();
    }

    @Test
    @DisplayName("개시 직후에는 아무도 들어가지 못한다")
    void 개시_직후() {
      assertThat(WaitingRoomPolicy.admitted(0L, 0L)).isFalse();
    }
  }

  @Nested
  @DisplayName("서버 지시 폴링 주기")
  class PollSeconds {

    private static final int MIN = 3;
    private static final int MAX = 60;

    @Test
    @DisplayName("ADR 0009 §3의 실측값을 재현한다 — N=10,000 · R=1,400이면 8초")
    void adr_수치() {
      // #546 에서 R 을 실측했다(약 1,400 RPS). 이 값이 곧 운영의 폴링 주기이므로 여기서 고정한다.
      assertThat(WaitingRoomPolicy.pollSeconds(10_000L, 1_400, MIN, MAX)).isEqualTo(8);
    }

    @Test
    @DisplayName("실측 전 보수값(R=400)이었다면 25초였다 — ADR 갱신 전후를 함께 남긴다")
    void adr_직전_보수값() {
      // #529 seat-counts 포화점을 빌린 하한이었고 실측치보다 3.5배 보수적이었다.
      assertThat(WaitingRoomPolicy.pollSeconds(10_000L, 400, MIN, MAX)).isEqualTo(25);
    }

    @Test
    @DisplayName("나머지가 있으면 올림한다 — 내림하면 폴링 RPS가 용량을 넘는다")
    void 올림() {
      // 4,001 / 400 = 10.0025. 내림하면 10초 주기가 되어 400.1 RPS로 용량을 넘긴다.
      assertThat(WaitingRoomPolicy.pollSeconds(4_001L, 400, MIN, MAX)).isEqualTo(11);
      assertThat(WaitingRoomPolicy.pollSeconds(4_000L, 400, MIN, MAX)).isEqualTo(10);
    }

    @Test
    @DisplayName("대기 인원이 적으면 하한까지만 줄인다")
    void 하한() {
      assertThat(WaitingRoomPolicy.pollSeconds(0L, 400, MIN, MAX)).isEqualTo(MIN);
      assertThat(WaitingRoomPolicy.pollSeconds(1L, 400, MIN, MAX)).isEqualTo(MIN);
    }

    @Test
    @DisplayName("대기 인원이 아주 많아도 상한을 넘지 않는다")
    void 상한() {
      assertThat(WaitingRoomPolicy.pollSeconds(1_000_000L, 400, MIN, MAX)).isEqualTo(MAX);
    }

    @Test
    @DisplayName("용량이 0 이하면 상한으로 눕는다 — 0으로 나누지 않는다")
    void 용량_없음() {
      assertThat(WaitingRoomPolicy.pollSeconds(10_000L, 0, MIN, MAX)).isEqualTo(MAX);
    }

    @Test
    @DisplayName("하한이 걸리는 구간에서도 폴링 RPS가 용량을 넘지 않는다")
    void 하한_구간_안전성() {
      // 하한 3초가 걸리기 시작하는 지점은 N = MIN × R = 1,200. 그 아래에서 N/MIN < R 이어야 한다.
      long waiting = 1_200L;
      int seconds = WaitingRoomPolicy.pollSeconds(waiting, 400, MIN, MAX);

      assertThat(seconds).isEqualTo(MIN);
      assertThat((double) waiting / seconds).isLessThanOrEqualTo(400.0);
    }
  }

  @Nested
  @DisplayName("남은 대기 인원")
  class RemainingWaiting {

    @Test
    @DisplayName("총 진입에서 허용 누적을 뺀다 — 승급자를 ZSET에서 지우지 않기 때문이다")
    void 차감() {
      assertThat(WaitingRoomPolicy.remainingWaiting(10_000L, 2_000L)).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("허용 누적이 총 진입을 넘어서도 음수가 되지 않는다")
    void 음수_없음() {
      assertThat(WaitingRoomPolicy.remainingWaiting(100L, 5_000L)).isZero();
    }
  }
}

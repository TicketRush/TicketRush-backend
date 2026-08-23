package com.ticketrush.queue;

/**
 * 대기열 승급·폴링 주기 판정(ADR 0009 §3).
 *
 * <p>Spring 의존이 없는 순수 함수만 둔다. 대기열에서 <b>틀리면 사용자가 영영 못 들어가는</b> 계산이 전부 여기 모여 있어야 단위 테스트로 고정할 수 있다 —
 * {@code WaitingRoomPolicyTest} 가 이 클래스만 검증한다.
 *
 * <p>승급을 상태(커서·스케줄러)로 관리하지 않고 <b>시각의 함수</b>로 계산하는 것이 이 설계의 핵심이다. 스케줄러 방식({@code @Scheduled} +
 * {@code ZPOPMIN N})은 ① 폴링이 0건이어도 초당 N개의 SET을 {@code noeviction} Redis에 계속 쓰고 ② ZPOPMIN으로 꺼낸
 * no-show의 승급 슬롯이 증발하며 ③ "스케줄러가 조용히 멈추면 대기열 영구 정지"라는 관측 불가능한 장애를 만든다. 랭크 기준 계산은 임계치가 시간에 선형으로 계속
 * 올라가므로 이탈자가 있어도 뒤 사람이 정시에 들어온다.
 */
public final class WaitingRoomPolicy {

  private WaitingRoomPolicy() {}

  /**
   * 지금까지 입장이 허용된 누적 인원.
   *
   * <p>{@code (경과 시간) × (초당 입장 허용량)}. 예매 경로 부하를 유입 규모와 무관하게 이 값으로 고정하는 것이 ADR 0009의 전부다.
   *
   * <p><b>허용량의 근거는 #555 계단 실측이다(ADR 0009 §3.5).</b> 이 문장은 원래 "#344 실측(booking 단독 258 RPS)의 아래로
   * 잡는다"였는데 <b>그 258 RPS는 예매 처리량이 아니다</b> — 98.61%가 좌석 점유 반려 409였고 실제 예매 생성은 약 3.3 RPS였다. 그 오독을 근거로
   * 인용하지 않는다. 현재 기본값 12는 완주(좌석맵 통과 + 예매 성공) 기준으로 16이 12보다 느리다는 실측에서 왔다.
   */
  public static long admittedThreshold(long nowMs, long openedAtMs, int admitRatePerSecond) {
    long elapsedMs = nowMs - openedAtMs;
    if (elapsedMs <= 0L || admitRatePerSecond <= 0) {
      return 0L;
    }
    // 초 단위로 먼저 나누면 계단이 생겨 같은 초 안의 요청이 전부 같은 판정을 받는다. ms 정밀도를 유지한다.
    // 최댓값은 대기열 TTL(6h = 21.6M ms) × rate 라 long 범위 안이다.
    return elapsedMs * admitRatePerSecond / 1000L;
  }

  /**
   * 이 순번이 입장 허용선 안인가.
   *
   * @param rank ZRANK 결과(0-based). 대기열에 없으면 음수를 넘긴다.
   */
  public static boolean admitted(long rank, long threshold) {
    return rank >= 0L && rank < threshold;
  }

  /**
   * 서버가 지시할 다음 폴링까지의 초.
   *
   * <p>ADR 0009 §3의 하한 산식 {@code T ≥ N / R}. N=10,000 · R=400이면 25초로, ADR이 적은 값과 일치한다. 클라이언트는 여기에
   * 지터를 더해 동기화된 버스트를 깬다.
   *
   * @param waiting 현재 대기 인원 N
   * @param statusRpsCapacity 상태 확인 경로가 감당하는 RPS R
   */
  public static int pollSeconds(
      long waiting, int statusRpsCapacity, int minSeconds, int maxSeconds) {
    if (statusRpsCapacity <= 0) {
      return maxSeconds;
    }
    long required = Math.max(0L, waiting);
    // ceil(N / R)
    long seconds = (required + statusRpsCapacity - 1) / statusRpsCapacity;
    return (int) Math.min(maxSeconds, Math.max(minSeconds, seconds));
  }

  /**
   * 아직 입장하지 못하고 남은 인원.
   *
   * <p>진입자는 승급돼도 ZSET에서 제거하지 않으므로(그래야 재폴링 시 순번을 다시 읽을 수 있다) 총 진입 인원에서 허용 누적분을 뺀다.
   */
  public static long remainingWaiting(long totalEnqueued, long threshold) {
    return Math.max(0L, totalEnqueued - threshold);
  }
}

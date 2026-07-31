package com.ticketrush.queue.dto;

/**
 * 대기열 상태 확인 응답(ADR 0009 §3·§4).
 *
 * <p>ADR §4가 요구한 "응답을 작게 유지한다"의 대상이다 — 참고 상한은 {@code seat-counts} 응답 203 bytes. 필드를 늘리기 전에 1만 명이
 * 25초마다 받아 가는 값이라는 것을 먼저 생각한다.
 *
 * <p>ADR §3은 "다음 폴링 <b>시각</b>"이라 적었지만 절대 시각은 서버·클라이언트 시계 동기를 요구하고 바이트도 더 든다. 상대 초를 준다.
 *
 * <p>{@code entryToken} 은 승급됐을 때만 채워지고(그전엔 null → {@code JsonInclude.NON_NULL} 로 응답에서 빠진다), 클라이언트는
 * 이 값을 {@code X-Entry-Token} 헤더로 예매 요청에 실어 보낸다.
 */
public record WaitingStatusResponse(
    long rank, long waiting, int nextPollAfterSeconds, String entryToken) {

  public static WaitingStatusResponse waiting(long rank, long waiting, int nextPollAfterSeconds) {
    return new WaitingStatusResponse(rank, waiting, nextPollAfterSeconds, null);
  }

  public static WaitingStatusResponse admitted(
      long waiting, int nextPollAfterSeconds, String entryToken) {
    return new WaitingStatusResponse(0L, waiting, nextPollAfterSeconds, entryToken);
  }
}

package com.ticketrush.queue.dto;

/**
 * 대기열 진입 응답.
 *
 * <p>{@code waitingToken} 은 불투명 문자열이다. 이후 상태 확인은 JWT가 아니라 이 토큰만 대조하므로(ADR 0009 §4) 폴링 경로에 서명 검증이 끼지
 * 않는다. 진입은 1인 1회라 여기서만 JWT를 태운다.
 */
public record EnqueueResponse(
    String waitingToken, long rank, long waiting, int nextPollAfterSeconds) {}

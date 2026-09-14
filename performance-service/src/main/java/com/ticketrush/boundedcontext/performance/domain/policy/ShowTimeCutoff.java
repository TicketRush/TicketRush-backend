package com.ticketrush.boundedcontext.performance.domain.policy;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * "공연 시작 시각이 지났다"를 판정하는 기준 시각 (#651).
 *
 * <p>{@code showDate}·{@code showTime}이 두 컬럼이라 비교도 두 값으로 한다. 한 {@link java.time.LocalDateTime}으로 넘겨
 * 호출부마다 날짜·시각을 쪼개면 목록 조건과 벌크 전환이 서로 다른 순간을 잘라 쓸 수 있다. 정책이 한 번 만든 이 값 객체를 두 경로가 그대로 받는다.
 *
 * @param date 기준 날짜 (Asia/Seoul)
 * @param time 기준 시각 (Asia/Seoul, 초 단위 절삭)
 */
public record ShowTimeCutoff(LocalDate date, LocalTime time) {}

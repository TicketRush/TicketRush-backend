package com.ticketrush.boundedcontext.booking.domain.policy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

/**
 * 환불 마감(D-7) 판정 기준을 소유한다 (#668).
 *
 * <p>공연 시작까지 7일 이상 남은 예매만 환불할 수 있다. 이 제한은 원래 프론트에만 있어 API 직접 호출로 우회됐고, 금전이 오가는 경로이므로 신뢰 경계인 서버에서 다시
 * 강제한다.
 *
 * <p><b>기준 시각은 공연 시작 일시</b>({@code showDate} + {@code showTime})를 Asia/Seoul 벽시계로 해석한 순간이다. 주입되는
 * {@code Clock}은 UTC({@code ClockConfig})이므로 {@code LocalDateTime.now()}로 비교하면 UTC 컨테이너에서 9시간 어긋난다
 * — 공연 일시는 서울 달력·시각으로 입력되기 때문이다. ADR 0020(#653)이 같은 이유로 세운 규율을 따른다.
 *
 * <p><b>경계(정확히 7일)는 허용</b>한다. 프론트 {@code isRefundableBooking}이 {@code >= 7}로 판정하므로, 여기서 {@code >}를
 * 쓰면 경계에서 "버튼은 활성인데 API는 409"인 불일치가 생긴다.
 */
@Component
public class RefundDeadlinePolicy {

  private static final ZoneId SHOW_ZONE = ZoneId.of("Asia/Seoul");

  // ponytail: 상수. 프론트 isRefundableBooking 이 7을 하드코딩하므로 설정으로 빼면 한쪽만 바뀌어 사일런트 불일치가 생긴다.
  private static final int DEADLINE_DAYS = 7;

  private final Clock clock;

  public RefundDeadlinePolicy(Clock clock) {
    this.clock = clock;
  }

  /** 공연 시작까지 {@value #DEADLINE_DAYS}일 이상 남았으면 환불을 허용한다. 경계는 포함한다. */
  public boolean allowsRefund(LocalDate showDate, LocalTime showTime) {
    Instant showAt = ZonedDateTime.of(showDate, showTime, SHOW_ZONE).toInstant();
    return !Duration.between(clock.instant(), showAt).minusDays(DEADLINE_DAYS).isNegative();
  }
}

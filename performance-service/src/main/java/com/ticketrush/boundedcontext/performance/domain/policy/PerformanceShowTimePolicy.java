package com.ticketrush.boundedcontext.performance.domain.policy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * 공연 시작 시각({@code showDate} + {@code showTime})과 예매 오픈 시각({@code bookingOpenAt})의 시간대 해석과 판정 기준을
 * 소유한다 (#651·#653, ADR 0020).
 *
 * <p><b>기준 문장:</b> 공연 시작 시각(showDate + showTime, Asia/Seoul)이 지났다 = 시작 시각 정각을 포함해 현재 시각과 같거나 이전이다.
 * 사용자 목록의 제외 조건({@code PerformanceRepositoryImpl.findByFilters})과 CLOSED 벌크 전환({@code
 * PerformanceRepository.bulkTransitionStatusByShowTimePassed})은 이 문장의 정확한 여집합이어야 한다 — 어긋나면 "목록에는
 * 없는데 ON_SALE"이거나 "목록에는 있는데 CLOSED"인 공연이 생긴다. 그래서 두 경로가 같은 {@link #cutoff()} 값을 받는다.
 *
 * <p><b>시간대는 Asia/Seoul 벽시계로 해석한다.</b> {@code show_date}·{@code show_time}은 어드민이 오프셋 없는 {@code
 * yyyy-MM-dd}·{@code HH:mm:ss}로 입력한 값이라 저장 시점에 시간대가 없다. 근거는 네 가지다 — 요청 DTO가 오프셋 없는 벽시계 형식이고, 한국
 * 서비스이며, 운영 MySQL 컨테이너가 {@code TZ: Asia/Seoul}이고, 시드·예시 값이 19:00~20:00대의 한국 공연 시각이다. 레포의 다른 시각
 * 규약(auditing·booking·payment)은 UTC이지만({@code docs/utc-timestamp-rollout.md}), 그것은 시스템이 <b>생성</b>하는
 * 시각의 규약이고 이 값은 사람이 <b>입력</b>하는 공연 시각이라 축이 다르다.
 *
 * <p><b>JVM 기본 시간대에 기대지 않는다.</b> 운영 컨테이너는 {@code TZ=UTC}라 {@code LocalDateTime.now()}는 UTC 벽시계를
 * 돌려주고, 그대로 KST 값과 비교하면 공연이 시작하고도 9시간 동안 목록에 남는다. common의 {@code Clock}(UTC)을 주입받아 존만 바꿔 쓰므로 어느
 * JVM에서 돌아도 같은 판정이 나오고, 테스트는 이 컴포넌트만 대체해 시각을 고정할 수 있다.
 *
 * <p><b>초 단위로 절삭한다.</b> {@code show_time}은 초 정밀도 컬럼이라 나노초가 섞인 파라미터로 비교하면 정각 판정이 드라이버·방언에 따라 흔들릴 수
 * 있다. 목록·전환이 같은 절삭값을 쓰므로 여집합은 유지된다.
 *
 * <p>같은 형식의 {@code bookingOpenAt}도 같은 해석이다 (#653). 어드민이 오프셋 없는 {@code yyyy-MM-dd HH:mm:ss}로 입력한 값이라
 * {@code show_date}·{@code show_time}과 축이 같고, 한 서비스 안에서 사람이 입력한 시각의 해석은 하나여야 한다. 오픈 벌크 전환({@code
 * PerformanceRepository.bulkTransitionStatusByBookingOpenAtDue})은 {@link #bookingOpenCutoff()} 값과
 * 비교한다 — 예전처럼 {@code LocalDateTime.now()}(JVM 존)와 비교하면 운영(UTC)에서 9시간 늦게 열린다.
 */
@Component
public class PerformanceShowTimePolicy {

  /**
   * {@code show_date}·{@code show_time}·{@code booking_open_at}을 해석하는 시간대. 근거는 클래스 문서와 ADR 0020.
   */
  public static final ZoneId SHOW_ZONE = ZoneId.of("Asia/Seoul");

  /**
   * 쪼개 저장된 공연 시작 일시를 하나로 합친다 (#671).
   *
   * <p>응답에 {@code show_at}을 실으려고 둔다. {@code show_date}·{@code show_time}은 {@code DATE}·{@code TIME}
   * 별개 컬럼이라 오프셋을 붙일 자리가 없어서, 존 표시가 필요한 소비자는 합쳐진 값을 받아야 한다. 합치는 규칙이 두 곳에 생기지 않도록 해석의 주인인 여기에 둔다 —
   * 결과값은 {@link #SHOW_ZONE} 벽시계이며, 직렬화 때 오프셋이 붙는다.
   *
   * <p>엔티티에서 두 컬럼은 {@code nullable = false}라 정상 경로에 null 이 없다. null 방어는 손으로 만든 DTO(테스트 픽스처 등)를 위한
   * 것이다.
   */
  public static LocalDateTime showAt(LocalDate showDate, LocalTime showTime) {
    return (showDate == null || showTime == null) ? null : LocalDateTime.of(showDate, showTime);
  }

  private final Clock clock;

  public PerformanceShowTimePolicy(Clock clock) {
    this.clock = clock;
  }

  /**
   * 지금 이 순간의 판정 기준. 목록 조건과 CLOSED 벌크 전환은 반드시 같은 호출의 결과를 받아야 한다.
   *
   * <p>한 번의 {@code now}에서 날짜와 시각을 함께 뽑는다. 따로 두 번 읽으면 자정 근처에서 날짜는 어제, 시각은 오늘 것이 섞일 수 있다.
   */
  public ShowTimeCutoff cutoff() {
    LocalDateTime now = seoulNow();

    return new ShowTimeCutoff(now.toLocalDate(), now.toLocalTime());
  }

  /**
   * 지금 이 순간의 예매 오픈 판정 기준 (#653). {@code booking_open_at}은 DATETIME 한 컬럼이라 비교값도 하나다.
   *
   * <p>{@link #cutoff()}와 같은 존·같은 초 절삭 규칙을 쓴다. 요청이 초 단위({@code yyyy-MM-dd HH:mm:ss})로 바인딩되므로 절삭은
   * 저장값을 바꾸지 않고, 파라미터에 나노초가 섞여 정각 판정이 흔들리는 것만 막는다.
   */
  public LocalDateTime bookingOpenCutoff() {
    return seoulNow();
  }

  /** 두 판정 기준이 같은 존·같은 절삭 규칙을 한 곳에서 공유한다. */
  private LocalDateTime seoulNow() {
    return LocalDateTime.now(clock.withZone(SHOW_ZONE)).truncatedTo(ChronoUnit.SECONDS);
  }
}

package com.ticketrush.global.config;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * booking-service 동기 조회({@link
 * com.ticketrush.boundedcontext.payment.out.apiclient.BookingRestClient})의 서킷브레이커 설정(#571).
 *
 * <p>결제 확정 경로는 요청마다 booking 을 한 번 왕복한다(#490). 서킷이 없으면 booking 이 <b>죽지 않고 느려지는</b> 순간 요청마다 톰캣 스레드가
 * read-timeout 만큼 묶여 결제 경로 전체가 함께 마른다. 서킷은 그 전파를 끊어 "booking 이 죽으면 결제 확정만 죽는다"로 범위를 좁힌다.
 *
 * <p><b>🔴 이 서킷은 fail-closed 위에 얹힌다.</b> 조회가 불가능하면 결제를 막는 것이 #490 의 결정이라, 서킷이 오탐으로 열리면 그 대가는 곧
 * <b>결제 확정 전건 503</b> 이다. ticket-service(#496)는 같은 설정을 검표 경로에 걸었지만 그쪽 오탐의 대가는 입장 거절이고, 이쪽은 매출이 멈춘다.
 * 그래서 임계값을 선례에서 복사하지 않고 #633 이 payment→booking 구간을 따로 실측했다.
 *
 * <p>그럼에도 서킷을 다는 이유는 ADR 0008 과 같은 축이다 — 가용성 손실이 정합성 손실보다 낫고, 서킷이 없을 때의 귀결은 "결제만 막히는 것"이 아니라 "톰캣
 * 스레드가 마르면서 취소·조회까지 함께 죽는 것"이기 때문이다.
 */
@Configuration
public class BookingCircuitBreakerConfig {

  /** 서킷 인스턴스 이름. {@code BookingRestClient} 가 같은 이름으로 생성하고 메트릭 라벨에도 이 값이 붙는다. */
  public static final String BOOKING_CIRCUIT_BREAKER = "booking";

  @Bean
  public Customizer<Resilience4JCircuitBreakerFactory> bookingCircuitBreakerCustomizer() {
    return factory ->
        factory.configure(
            builder -> builder.circuitBreakerConfig(bookingConfig()), BOOKING_CIRCUIT_BREAKER);
  }

  /**
   * 임계값의 근거는 #633 실측이다({@code
   * load-tests/k6/results/260911-633-payment-booking-roundtrip/report.md}). <b>다섯 값의 근거 등급이 서로 다르므로
   * 아래에 하나씩 밝혀 둔다</b> — 근거 없는 값을 근거 있는 값처럼 읽으면 오탐이 났을 때 엉뚱한 축을 건드리게 된다.
   *
   * <p><b>{@code slowCallDurationThreshold} = 100ms — 확정.</b> 세 run(워밍업 2 · 정상~피크 1) 모두 <b>50~100ms
   * 구간의 건수가 정확히 0</b> 이었다. 50ms 로 낮춰도 초과 건수가 같아 검출력을 잃지 않으면서 여유만 두 배가 되는 지점이 100ms 다. 검증:
   * read-timeout(1s)보다 한참 아래이고, 워밍업 p99 중 큰 쪽(W2 32.133ms)의 3.1배다. 🔴 <b>한계</b> — 이 값은 <b>재기동</b>
   * 워밍업에서 얻었고 <b>배포 직후 구간은 재현하지 못했다.</b> #496 이 300ms 에서 겪은 오탐(실패 0건인데 차단 1,472건)이 바로 그 구간이므로, 첫 배포
   * 직후 {@code resilience4j_circuitbreaker_state} 를 반드시 지켜봐야 한다. 오탐이 나면 #496 선례대로 임계를 올린다.
   *
   * <p><b>{@code slowCallRateThreshold} = 50% — 잠정.</b> Resilience4j 기본값이다. 실측은 정상 구간 0% · 워밍업 최악
   * 0.554% 로 이 값을 <b>반증하지 않을 뿐 지지하지도 않는다</b>(표본이 워밍업 run 둘뿐이고 그 꼬리가 서로 7배 차이). 반대편 위험도 미검증이다 — 50%
   * 는 "느린 호출이 절반을 넘을 때까지 열리지 않는다"는 뜻이기도 해서, booking 이 서서히 느려지는 시나리오에서 이 지연이 적절한지는 #633 이 답하지 못했다.
   *
   * <p><b>{@code failureRateThreshold} = 50% — 잠정(실측에서 도출되지 않음).</b> 🔴 <b>서킷이 보는 구간의 실패 표본이
   * 0건이다.</b> 세 run 전부 왕복 Timer 의 {@code outcome=failed} 가 0 이었다. k6 가 W1 에서 503 을 18건 셌지만 그 실패는
   * payment 의 계측 어디에도 없었다 — 즉 <b>서킷이 감쌀 구간 밖에서 일어난 실패라 이 임계와 무관하다</b>(리포트 §4.3). 이 값은 기본값을 채택한 것이지
   * 측정한 것이 아니다.
   *
   * <p><b>{@code slidingWindowType} = TIME_BASED(60초) · {@code minimumNumberOfCalls} = 10 — 잠정.</b>
   * ticket-service 는 COUNT_BASED(20건)를 쓰지만 여기서는 <b>시간 기반을 택했다.</b> #633 회차는 합성 단일 사용자 트래픽이라 운영 도달량을
   * 주지 못했고, Rate Limit 은 <b>사용자당</b>인데 서킷 윈도우는 <b>인스턴스 전역</b>이라 둘을 같은 축으로 환산할 수도 없다. 건수 윈도우는 저트래픽
   * 구간에서 <b>어제의 실패가 오늘의 개폐를 결정</b>하게 만드는데, 시간 윈도우는 그 stale 표본 문제를 없앤다. ⚠ 그래도 호출량 가정이 완전히 사라지지는 않는다
   * — {@code minimumNumberOfCalls} 는 윈도우 종류와 무관하게 남는 <b>개폐 게이팅 조건</b>이고(그 수를 못 채우면 서킷은 영영 열리지 않는다)
   * 10 의 근거는 없다. 과대 설정의 신호는 "장애 구간에서 {@code kind="failed"} 는 오르는데 {@code state} 가 closed 에 머무는
   * 것"이다.
   *
   * <p><b>{@code waitDurationInOpenState} = 10초 · {@code permittedNumberOfCallsInHalfOpenState} = 3
   * — 근거 없음.</b> ticket-service 값을 그대로 차용했다. #633 은 회복 곡선을 시간축으로 분해하지 못해(콜드 첫 호출 326.6ms 단일 표본뿐) 이
   * 값을 지지하지도 반증하지도 않는다. 틀렸다면 서킷 상태가 open→half_open→open 으로 되풀이되는 <b>플래핑</b>으로 드러난다.
   *
   * <p><b>{@code maxWaitDurationInHalfOpenState} = 60초 — 영구 갇힘 방지용이다.</b> 기본값 0 은 "타이머 없음"인데, 그
   * 상태에서는 HALF_OPEN 이 <b>빠져나오지 못하는 경우가 있다.</b> resilience4j 가 호출을 감싸는 지점의 catch 는 {@code Exception}
   * 이라 {@code Error} 가 나면 성공·실패 어느 쪽으로도 기록되지 않고 permit 도 반환되지 않는다. HALF_OPEN 의 permit 3개가 그렇게 소진되면
   * 상태 전이를 일으킬 '기록된 호출'이 영영 생기지 않아 <b>조회가 전건 차단된 채 고정</b>된다 — fail-closed 경로에서 그것은 결제 확정 영구 503 이고,
   * 재기동이나 킬 스위치 말고는 빠져나올 길이 없다. 이 상한이 있으면 그 구간이 60초 뒤 OPEN 으로 떨어져 정상 회복 절차를 다시 탄다. 값에 실측 근거는 없고
   * 슬라이딩 윈도우와 같은 60초로 맞췄다. 저트래픽이라 HALF_OPEN 에서 3콜을 모으는 데 60초가 넘으면 OPEN↔HALF_OPEN 을 오가는데, 그때도 매 주기
   * 3콜씩 통과하므로 회복 탐지는 계속된다.
   *
   * <p>public 인 이유는 테스트가 {@code CircuitBreakerConfig.from(...)} 으로 이 설정을 파생해 윈도우·대기 시간만 줄여 쓰기 위함이다.
   * 판정 규칙({@link #isNotACircuitSignal})을 테스트가 손으로 복제하면 규칙이 바뀌어도 테스트가 눈치채지 못한다(ticket-service 선례와 같은
   * 규율).
   */
  public static CircuitBreakerConfig bookingConfig() {
    return CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
        .slidingWindowSize(60)
        .minimumNumberOfCalls(10)
        .failureRateThreshold(50)
        .slowCallDurationThreshold(Duration.ofMillis(100))
        .slowCallRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(10))
        .permittedNumberOfCallsInHalfOpenState(3)
        .maxWaitDurationInHalfOpenState(Duration.ofSeconds(60))
        .ignoreException(BookingCircuitBreakerConfig::isNotACircuitSignal)
        .build();
  }

  /**
   * 예매 없음(404 → {@code BOOKING_NOT_FOUND})은 booking-service 가 <b>정상 동작 중일 때</b> 나오는 응답이므로 서킷의 판정에서
   * 아예 뺀다. 서킷 실패로 세면 존재하지 않는 예매로 결제를 반복 시도하는 것만으로 서킷이 열려 멀쩡한 결제가 전부 503 이 된다.
   *
   * <p>🔴 <b>{@code recordException} 이 아니라 {@code ignoreException} 인 것이 중요하다.</b> 전자는 "실패로 세지 않는다"가
   * 곧 <b>성공으로 센다</b>는 뜻이라(Resilience4j 는 그 경우 {@code onSuccess} 를 탄다) 셋이 따라온다 — ① 404 홍수가 {@code
   * minimumNumberOfCalls} 를 대신 채워 게이팅을 열어 주고, ② 성공 분모를 불려 실패율을 희석하며(404 와 실패가 1:1 로 섞이면 50% 에 닿지 않아
   * booking 이 아파도 열리지 않는다), ③ 느린호출 판정에도 들어간다. 후자는 permit 을 돌려주고 <b>창에서 통째로 제외</b>한다. 우리가 원하는 것은 "이
   * 응답은 서킷이 볼 신호가 아니다"이므로 후자다.
   *
   * <p>이 분류는 {@code BookingRestClient} 가 Timer 에 붙이는 {@code outcome} 태그와 <b>같은 경계</b>다 — {@code
   * not_found} 는 서킷 신호가 아니고, {@code failed} 는 서킷 신호다. 두 축이 어긋나면 #633 이 만든 관측으로 서킷 동작을 설명할 수 없게 된다.
   *
   * <p>⚠ 소유자 불일치(#572)도 {@code BOOKING_NOT_FOUND} 로 응답이 동일화되지만, 그 판정은 조회가 200 을 반환한 <b>뒤</b>
   * UseCase 가 하는 것이라 이 클라이언트를 거치지 않는다. 즉 여기 걸리는 {@code BOOKING_NOT_FOUND} 는 booking 이 실제로 404 를 준
   * 건뿐이다.
   */
  private static boolean isNotACircuitSignal(Throwable throwable) {
    return throwable instanceof BusinessException businessException
        && businessException.getErrorStatus() == ErrorStatus.BOOKING_NOT_FOUND;
  }
}

package com.ticketrush.boundedcontext.payment.app.scheduler;

import com.ticketrush.boundedcontext.payment.app.usecase.PaymentRecoverChargedExpiredBookingUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 과금됐으나 예매가 만료된 건을 주기적으로 찾아 자동 환불한다 (#607).
 *
 * <p><b>기본값이 비활성이다.</b> {@code ReplayRefundFailureSignalScheduler}(#574)가 {@code matchIfMissing =
 * true} 인 것과 다르며, 차이의 이유는 이 작업에 <b>PG 환불이라는 되돌릴 수 없는 부작용</b>이 있다는 것이다. 켜는 순간 첫 랩이 과거에 쌓인 사고 건을 전량
 * 환불하므로, 배포 전에 잔량을 실측하고 그 규모를 알고 켠다(ADR 0015 · #441).
 *
 * <p>주기가 짧은 이유는 대상 집합이 스스로 줄지 않기 때문이다. {@code expired_booking} 은 해결돼도 행이 남아 한 바퀴 길이가 누적 만료 건수에
 * 비례하고, 실제 복구 지연 상한은 이 주기가 아니라 {@code ceil(만료 건수 / batch-size) × interval} 이다.
 *
 * <p>ShedLock 미사용 근거는 {@link PaymentRecoverChargedExpiredBookingUseCase} 의 javadoc 참고 — #574 와 결론은
 * 같되 근거가 다르다.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.charged-expired-recovery",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class RecoverChargedExpiredBookingScheduler {

  private final PaymentRecoverChargedExpiredBookingUseCase
      paymentRecoverChargedExpiredBookingUseCase;
  private final int batchSize;
  private final int graceMinutes;
  private final int maxRefundsPerLap;

  public RecoverChargedExpiredBookingScheduler(
      PaymentRecoverChargedExpiredBookingUseCase paymentRecoverChargedExpiredBookingUseCase,
      @Value("${app.charged-expired-recovery.batch-size:500}") int batchSize,
      @Value("${app.charged-expired-recovery.grace-minutes:30}") int graceMinutes,
      @Value("${app.charged-expired-recovery.max-refunds-per-lap:50}") int maxRefundsPerLap) {
    this.paymentRecoverChargedExpiredBookingUseCase = paymentRecoverChargedExpiredBookingUseCase;
    this.batchSize = requirePositive(batchSize, "app.charged-expired-recovery.batch-size");
    this.graceMinutes = requirePositive(graceMinutes, "app.charged-expired-recovery.grace-minutes");
    this.maxRefundsPerLap =
        requirePositive(maxRefundsPerLap, "app.charged-expired-recovery.max-refunds-per-lap");
  }

  /**
   * 0 이나 음수를 기동 시점에 막는다.
   *
   * <p>세 값 모두 0 이 "제한 없음"이 아니라 <b>고장</b>이라 그대로 두면 조용히 잘못 돈다. {@code batch-size=0} 은 {@code
   * PageRequest.of(0, 0)} 이 매 주기 예외를 던져 영구 무동작이 되고, {@code grace-minutes=0} 이하는 만료 직후 건까지 즉시 환불
   * 대상으로 삼으며, {@code max-refunds-per-lap=0} 은 PG 호출을 통째로 막는다. 어느 쪽도 로그를 보지 않으면 드러나지 않는다 — #573 이
   * "대기값 0 은 대기 생략이 아니라 킬 스위치여야 한다"로 남긴 교훈과 같은 축이라, 기동을 실패시켜 배포 시점에 드러낸다.
   */
  private static int requirePositive(int value, String propertyName) {
    if (value <= 0) {
      throw new IllegalArgumentException(
          "%s 는 1 이상이어야 합니다. 현재 값: %d".formatted(propertyName, value));
    }
    return value;
  }

  @Scheduled(fixedDelayString = "${app.charged-expired-recovery.interval-ms:60000}")
  public void recover() {
    paymentRecoverChargedExpiredBookingUseCase.execute(batchSize, graceMinutes, maxRefundsPerLap);
  }
}

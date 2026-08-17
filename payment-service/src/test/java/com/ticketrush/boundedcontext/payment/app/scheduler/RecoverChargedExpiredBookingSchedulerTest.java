package com.ticketrush.boundedcontext.payment.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.usecase.PaymentRecoverChargedExpiredBookingUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/**
 * 스케줄러가 <b>명시적으로 켤 때만</b> 등록되는지 고정한다 (#607).
 *
 * <p>이 기본값은 취향이 아니라 안전장치다. 켜지는 순간 첫 랩이 과거에 쌓인 사고 건을 전량 환불하고, PG 환불은 되돌릴 수 없다. 누가 {@code
 * matchIfMissing} 을 {@code true} 로 바꾸거나 프로퍼티 이름을 흘리면 <b>배포만으로</b> 그 일이 벌어지므로 테스트로 못박는다.
 */
class RecoverChargedExpiredBookingSchedulerTest {

  private final PaymentRecoverChargedExpiredBookingUseCase useCase =
      mock(PaymentRecoverChargedExpiredBookingUseCase.class);

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withBean(
            PropertySourcesPlaceholderConfigurer.class, PropertySourcesPlaceholderConfigurer::new)
        .withBean(PaymentRecoverChargedExpiredBookingUseCase.class, () -> useCase)
        .withUserConfiguration(RecoverChargedExpiredBookingScheduler.class);
  }

  @Test
  @DisplayName("성공: 설정이 없으면 스케줄러 빈이 등록되지 않는다")
  void not_registered_by_default() {
    runner()
        .run(
            context ->
                assertThat(context).doesNotHaveBean(RecoverChargedExpiredBookingScheduler.class));
  }

  @Test
  @DisplayName("성공: enabled=false 면 스케줄러 빈이 등록되지 않는다")
  void not_registered_when_disabled() {
    runner()
        .withPropertyValues("app.charged-expired-recovery.enabled=false")
        .run(
            context ->
                assertThat(context).doesNotHaveBean(RecoverChargedExpiredBookingScheduler.class));
  }

  @Test
  @DisplayName("성공: enabled=true 면 설정한 배치·유예·환불 상한 값으로 UseCase 를 호출한다")
  void registered_and_passes_configured_values() {
    runner()
        .withPropertyValues(
            "app.charged-expired-recovery.enabled=true",
            "app.charged-expired-recovery.batch-size=7",
            "app.charged-expired-recovery.grace-minutes=11",
            "app.charged-expired-recovery.max-refunds-per-lap=3")
        .run(
            context -> {
              assertThat(context).hasSingleBean(RecoverChargedExpiredBookingScheduler.class);

              context.getBean(RecoverChargedExpiredBookingScheduler.class).recover();

              // 값이 뒤바뀌면 유예 7분·배치 11건으로 도는데, 셋 다 int 라 컴파일러가 잡아주지 않는다.
              verify(useCase).execute(7, 11, 3);
            });
  }

  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({
    "app.charged-expired-recovery.batch-size, 0",
    "app.charged-expired-recovery.batch-size, -1",
    "app.charged-expired-recovery.grace-minutes, 0",
    "app.charged-expired-recovery.grace-minutes, -5",
    "app.charged-expired-recovery.max-refunds-per-lap, 0",
    "app.charged-expired-recovery.max-refunds-per-lap, -3"
  })
  @DisplayName("성공: 0 이하 설정값이면 기동에 실패한다")
  void fails_to_start_on_non_positive_values(String property, String value) {
    // 0 은 "제한 없음"이 아니라 고장이다 — batch-size=0 은 매 주기 예외로 영구 무동작이 되고,
    // grace=0 이하는 만료 직후 건까지 즉시 환불 대상으로 삼는다. 어느 쪽도 로그를 보지 않으면
    // 드러나지 않으므로 배포 시점에 터뜨린다(#573 의 "0 은 킬 스위치여야 한다"와 같은 축).
    runner()
        .withPropertyValues("app.charged-expired-recovery.enabled=true", property + "=" + value)
        .run(context -> assertThat(context).hasFailed());
  }
}

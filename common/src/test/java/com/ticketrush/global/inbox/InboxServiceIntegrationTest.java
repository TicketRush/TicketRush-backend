package com.ticketrush.global.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.jpa.config.JpaConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * 실제 트랜잭션·리포지토리로 {@link InboxService#runIfFirst}를 검증한다(mock이 아닌 실동작). 원자성 seam(비즈니스 콜백 + inbox 기록이
 * 한 트랜잭션)을 실제 DB에서 확인한다.
 *
 * <p>{@code @DataJpaTest}는 메트릭 익스포트를 비활성화해 {@link io.micrometer.core.instrument.MeterRegistry} 빈이
 * 없으므로, {@link InboxService}(#335 계측) 생성을 위해 {@link SimpleMeterRegistry}를 함께 가져온다.
 */
@DataJpaTest
@Import({JpaConfig.class, InboxService.class, SimpleMeterRegistry.class})
class InboxServiceIntegrationTest {

  @Autowired private InboxService inboxService;

  @Autowired private InboxRepository inboxRepository;

  private static final String GROUP = "seat-group";

  private DomainEventEnvelope envelope(String eventId) {
    return new DomainEventEnvelope(
        eventId, "BookingCanceledEvent", Instant.now(), "booking-canceled-topic", "payload", null);
  }

  @Test
  @DisplayName("최초 수신이면 실제 트랜잭션에서 비즈니스를 실행하고 inbox row를 남기며 true를 반환한다")
  void runIfFirst_processes_and_records_in_real_tx() {
    // given
    AtomicInteger runs = new AtomicInteger();

    // when
    boolean processed = inboxService.runIfFirst(GROUP, envelope("evt-1"), runs::incrementAndGet);

    // then
    assertThat(processed).isTrue();
    assertThat(runs.get()).isEqualTo(1);
    assertThat(inboxRepository.existsByConsumerGroupAndEventId(GROUP, "evt-1")).isTrue();
  }

  @Test
  @DisplayName("같은 (group, eventId)를 다시 처리하면 inbox 기록으로 인해 비즈니스를 실행하지 않고 false를 반환한다")
  void runIfFirst_skips_already_recorded() {
    // given: 최초 처리로 inbox에 기록해 둔다
    AtomicInteger runs = new AtomicInteger();
    inboxService.runIfFirst(GROUP, envelope("evt-2"), runs::incrementAndGet);

    // when: 동일 (group, eventId) 재처리
    boolean processed = inboxService.runIfFirst(GROUP, envelope("evt-2"), runs::incrementAndGet);

    // then: 두 번째는 스킵되어 비즈니스가 다시 실행되지 않는다
    assertThat(processed).isFalse();
    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("비즈니스가 예외를 던지면 예외가 전파되고 inbox에 기록되지 않는다")
  void runIfFirst_does_not_record_when_business_fails() {
    // when & then
    assertThatThrownBy(
            () ->
                inboxService.runIfFirst(
                    GROUP,
                    envelope("evt-3"),
                    () -> {
                      throw new IllegalStateException("business 실패");
                    }))
        .isInstanceOf(IllegalStateException.class);

    // then: 실패한 처리는 inbox에 남지 않아 재전달 시 재처리될 수 있다
    assertThat(inboxRepository.existsByConsumerGroupAndEventId(GROUP, "evt-3")).isFalse();
  }
}

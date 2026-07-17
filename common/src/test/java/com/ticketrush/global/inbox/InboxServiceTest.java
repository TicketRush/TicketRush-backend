package com.ticketrush.global.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class InboxServiceTest {

  private InboxService inboxService;

  private SimpleMeterRegistry meterRegistry;

  @Mock private InboxRepository inboxRepository;

  private static final String GROUP = "seat-group";

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    inboxService = new InboxService(inboxRepository, meterRegistry);
  }

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "evt-1", "BookingCanceledEvent", Instant.now(), "booking-canceled-topic", "payload", null);
  }

  @Test
  @DisplayName("최초 수신이면 business를 실행하고 inbox에 기록한 뒤 true를 반환한다")
  void runIfFirst_processes_when_new() {
    // given
    given(inboxRepository.existsByConsumerGroupAndEventId(GROUP, "evt-1")).willReturn(false);
    AtomicBoolean ran = new AtomicBoolean(false);

    // when
    boolean processed = inboxService.runIfFirst(GROUP, envelope(), () -> ran.set(true));

    // then
    assertThat(processed).isTrue();
    assertThat(ran).isTrue();
    verify(inboxRepository).saveAndFlush(any(InboxEntity.class));
  }

  @Test
  @DisplayName("이미 처리된 이벤트면 business를 실행하지 않고 기록도 하지 않으며 false를 반환한다")
  void runIfFirst_skips_when_duplicate() {
    // given
    given(inboxRepository.existsByConsumerGroupAndEventId(GROUP, "evt-1")).willReturn(true);
    AtomicBoolean ran = new AtomicBoolean(false);

    // when
    boolean processed = inboxService.runIfFirst(GROUP, envelope(), () -> ran.set(true));

    // then
    assertThat(processed).isFalse();
    assertThat(ran).isFalse();
    verify(inboxRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("business가 예외를 던지면 그대로 전파되고 inbox에 기록하지 않는다(롤백 유도)")
  void runIfFirst_propagates_business_exception_without_recording() {
    // given
    given(inboxRepository.existsByConsumerGroupAndEventId(GROUP, "evt-1")).willReturn(false);

    // when & then
    assertThatThrownBy(
            () ->
                inboxService.runIfFirst(
                    GROUP,
                    envelope(),
                    () -> {
                      throw new IllegalStateException("business 실패");
                    }))
        .isInstanceOf(IllegalStateException.class);

    verify(inboxRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("동시 중복으로 inbox unique가 경합하면 saveAndFlush의 DIVE를 DuplicateEventException으로 변환해 던진다")
  void runIfFirst_translates_unique_violation_to_duplicate_event() {
    // given
    given(inboxRepository.existsByConsumerGroupAndEventId(GROUP, "evt-1")).willReturn(false);
    given(inboxRepository.saveAndFlush(any(InboxEntity.class)))
        .willThrow(new DataIntegrityViolationException("duplicate (group,event_id)"));

    // when & then: 리스너가 비즈니스 무결성 위반과 구분해 멱등 처리할 수 있도록 전용 예외로 변환한다
    assertThatThrownBy(() -> inboxService.runIfFirst(GROUP, envelope(), () -> {}))
        .isInstanceOf(DuplicateEventException.class);
  }

  @Test
  @DisplayName("consumer_group과 eventId로 중복 여부를 조회한다")
  void runIfFirst_checks_by_group_and_event_id() {
    // given
    given(inboxRepository.existsByConsumerGroupAndEventId(anyString(), anyString()))
        .willReturn(false);

    // when
    inboxService.runIfFirst(GROUP, envelope(), () -> {});

    // then
    verify(inboxRepository).existsByConsumerGroupAndEventId(GROUP, "evt-1");
  }

  @Test
  @DisplayName("최초 처리 성공 시 KAFKA_INBOX processed 카운터가 증가한다(#335)")
  void runIfFirst_increments_processed_counter_when_new() {
    // given
    given(inboxRepository.existsByConsumerGroupAndEventId(GROUP, "evt-1")).willReturn(false);

    // when
    inboxService.runIfFirst(GROUP, envelope(), () -> {});

    // then
    double count =
        meterRegistry
            .get(MetricNames.KAFKA_INBOX)
            .tag(MetricNames.TAG_CONSUMER_GROUP, GROUP)
            .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_PROCESSED)
            .counter()
            .count();
    assertThat(count).isEqualTo(1.0);
  }

  @Test
  @DisplayName("중복 처리 시 KAFKA_INBOX duplicate 카운터가 증가한다(#335)")
  void runIfFirst_increments_duplicate_counter_when_already_processed() {
    // given
    given(inboxRepository.existsByConsumerGroupAndEventId(GROUP, "evt-1")).willReturn(true);

    // when
    inboxService.runIfFirst(GROUP, envelope(), () -> {});

    // then
    double count =
        meterRegistry
            .get(MetricNames.KAFKA_INBOX)
            .tag(MetricNames.TAG_CONSUMER_GROUP, GROUP)
            .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_DUPLICATE)
            .counter()
            .count();
    assertThat(count).isEqualTo(1.0);
  }
}

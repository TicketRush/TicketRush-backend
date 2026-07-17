package com.ticketrush.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ticketrush.global.event.DomainEventEnvelope;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxStatusTransitionTest {

  @InjectMocks private OutboxStatusTransition outboxStatusTransition;

  @Mock private OutboxRepository outboxRepository;
  @Mock private OutboxProperties outboxProperties;

  private OutboxEntity row() {
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            "evt-1",
            "BookingCreatedEvent",
            Instant.parse("2026-05-27T06:00:00Z"),
            "booking-created-topic",
            "{}",
            "trace-1");
    return OutboxEntity.from(envelope, "Booking", "100", "100");
  }

  @Test
  @DisplayName("markSuccess: SENT로 전이하고 publishedAt을 기록한다")
  void markSuccess_transitions_to_sent() {
    OutboxEntity row = row();
    given(outboxRepository.findById(1L)).willReturn(Optional.of(row));

    outboxStatusTransition.markSuccess(1L);

    assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
    assertThat(row.getPublishedAt()).isNotNull();
  }

  @Test
  @DisplayName("markFail: 재시도 상한 미만이면 FAILED로 전이하고 null을 반환한다")
  void markFail_transitions_to_failed_below_cap() {
    OutboxEntity row = row();
    given(outboxRepository.findById(1L)).willReturn(Optional.of(row));
    given(outboxProperties.getMaxRetries()).willReturn(3);

    OutboxStatusTransition.DeadInfo result = outboxStatusTransition.markFail(1L, "boom");

    assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
    assertThat(row.getRetryCount()).isEqualTo(1);
    assertThat(row.getLastError()).isEqualTo("boom");
    assertThat(result).isNull();
  }

  @Test
  @DisplayName("markFail: 재시도 상한에 도달하면 DEAD로 전이하고 DeadInfo를 반환한다")
  void markFail_transitions_to_dead_at_cap() {
    OutboxEntity row = row();
    given(outboxRepository.findById(1L)).willReturn(Optional.of(row));
    given(outboxProperties.getMaxRetries()).willReturn(1);

    OutboxStatusTransition.DeadInfo result = outboxStatusTransition.markFail(1L, "boom");

    assertThat(row.getStatus()).isEqualTo(OutboxStatus.DEAD);
    assertThat(row.getRetryCount()).isEqualTo(1);
    assertThat(result).isNotNull();
    assertThat(result.eventId()).isEqualTo("evt-1");
    assertThat(result.maxRetries()).isEqualTo(1);
  }

  @Test
  @DisplayName("이미 SENT면 늦게 도착한 실패 콜백이 상태를 역전시키지 않고 null을 반환한다")
  void markFail_does_not_revert_already_sent_row() {
    OutboxEntity row = row();
    given(outboxRepository.findById(1L)).willReturn(Optional.of(row));
    given(outboxProperties.getMaxRetries()).willReturn(1);

    outboxStatusTransition.markSuccess(1L);
    OutboxStatusTransition.DeadInfo result = outboxStatusTransition.markFail(1L, "late failure");

    assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
    assertThat(result).isNull();
  }

  @Test
  @DisplayName("row가 없으면 예외 없이 null을 반환한다")
  void ignores_when_row_not_found() {
    given(outboxRepository.findById(99L)).willReturn(Optional.empty());

    OutboxStatusTransition.DeadInfo result = outboxStatusTransition.markFail(99L, "err");

    assertThat(result).isNull();
  }
}

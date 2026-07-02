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
class OutboxStatusUpdaterTest {

  @InjectMocks private OutboxStatusUpdater outboxStatusUpdater;

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

    outboxStatusUpdater.markSuccess(1L);

    assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
    assertThat(row.getPublishedAt()).isNotNull();
  }

  @Test
  @DisplayName("markFail: 재시도 상한 미만이면 FAILED로 전이하고 retryCount를 올린다")
  void markFail_transitions_to_failed_below_cap() {
    OutboxEntity row = row();
    given(outboxRepository.findById(1L)).willReturn(Optional.of(row));
    given(outboxProperties.getMaxRetries()).willReturn(3);

    outboxStatusUpdater.markFail(1L, "boom");

    assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
    assertThat(row.getRetryCount()).isEqualTo(1);
    assertThat(row.getLastError()).isEqualTo("boom");
  }

  @Test
  @DisplayName("markFail: 재시도 상한에 도달하면 DEAD로 전이한다")
  void markFail_transitions_to_dead_at_cap() {
    OutboxEntity row = row();
    given(outboxRepository.findById(1L)).willReturn(Optional.of(row));
    given(outboxProperties.getMaxRetries()).willReturn(1);

    outboxStatusUpdater.markFail(1L, "boom");

    assertThat(row.getStatus()).isEqualTo(OutboxStatus.DEAD);
    assertThat(row.getRetryCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("row가 없으면 예외 없이 무시한다")
  void ignores_when_row_not_found() {
    given(outboxRepository.findById(99L)).willReturn(Optional.empty());

    outboxStatusUpdater.markSuccess(99L);
  }
}

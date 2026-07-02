package com.ticketrush.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxRetentionServiceTest {

  @InjectMocks private OutboxRetentionService outboxRetentionService;

  @Mock private OutboxRepository outboxRepository;
  @Mock private OutboxProperties outboxProperties;

  @Test
  @DisplayName("보존 기간이 지난 SENT row를 자기 소유 aggregateType 기준으로 삭제한다")
  void purge_deletes_expired_sent_rows() {
    // given
    given(outboxProperties.getAggregateTypes()).willReturn(List.of("Booking"));
    given(outboxProperties.getRetentionHours()).willReturn(72L);
    given(
            outboxRepository.deleteSentBefore(
                eq(List.of("Booking")), eq(OutboxStatus.SENT), any(LocalDateTime.class)))
        .willReturn(5);

    // when
    int deleted = outboxRetentionService.purgeExpiredSent();

    // then
    assertThat(deleted).isEqualTo(5);
    ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(outboxRepository)
        .deleteSentBefore(eq(List.of("Booking")), eq(OutboxStatus.SENT), thresholdCaptor.capture());
    // threshold는 대략 now - 72h (경계 오차 감안해 과거 시각인지만 확인)
    assertThat(thresholdCaptor.getValue()).isBefore(LocalDateTime.now());
  }

  @Test
  @DisplayName("소유 aggregateType이 비어 있으면 삭제하지 않는다")
  void purge_does_nothing_when_no_owned_aggregate_types() {
    // given
    given(outboxProperties.getAggregateTypes()).willReturn(List.of());

    // when
    int deleted = outboxRetentionService.purgeExpiredSent();

    // then
    assertThat(deleted).isZero();
    verify(outboxRepository, never()).deleteSentBefore(any(), any(), any());
  }
}

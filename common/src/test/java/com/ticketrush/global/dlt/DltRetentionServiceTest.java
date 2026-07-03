package com.ticketrush.global.dlt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DltRetentionServiceTest {

  @InjectMocks private DltRetentionService dltRetentionService;

  @Mock private DeadLetterRecordRepository repository;
  @Mock private DltMonitorProperties properties;

  @Test
  @DisplayName("보존 기간이 지난 dead_letter_record를 threshold 기준으로 삭제한다")
  void purge_deletes_expired_records() {
    // given
    given(properties.getRetentionDays()).willReturn(30);
    given(repository.deleteCreatedBefore(any(LocalDateTime.class))).willReturn(7);

    // when
    int deleted = dltRetentionService.purgeExpired();

    // then
    assertThat(deleted).isEqualTo(7);
    ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(repository).deleteCreatedBefore(thresholdCaptor.capture());
    // threshold는 대략 now - 30일. 경계 오차를 감안해 [now-31일, now-29일] 범위인지만 확인한다.
    LocalDateTime threshold = thresholdCaptor.getValue();
    assertThat(threshold).isBefore(LocalDateTime.now().minusDays(29));
    assertThat(threshold).isAfter(LocalDateTime.now().minusDays(31));
  }

  @Test
  @DisplayName("삭제 대상이 없으면 0을 반환한다")
  void purge_returns_zero_when_nothing_deleted() {
    // given
    given(properties.getRetentionDays()).willReturn(30);
    given(repository.deleteCreatedBefore(any(LocalDateTime.class))).willReturn(0);

    // when
    int deleted = dltRetentionService.purgeExpired();

    // then
    assertThat(deleted).isZero();
  }

  @Test
  @DisplayName("retentionDays가 0 이하이면 전량 삭제 사고를 막기 위해 purge를 건너뛰고 0을 반환한다")
  void purge_skips_and_returns_zero_when_retention_days_non_positive() {
    // given: 0은 사실상 전량 삭제를 의미하므로 스킵
    given(properties.getRetentionDays()).willReturn(0);

    // when
    int deleted = dltRetentionService.purgeExpired();

    // then
    assertThat(deleted).isZero();
    verify(repository, never()).deleteCreatedBefore(any());
  }
}

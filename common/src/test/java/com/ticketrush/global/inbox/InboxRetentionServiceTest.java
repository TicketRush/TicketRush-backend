package com.ticketrush.global.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
class InboxRetentionServiceTest {

  @InjectMocks private InboxRetentionService inboxRetentionService;

  @Mock private InboxRetentionBatchDeleter batchDeleter;
  @Mock private InboxRetentionProperties properties;

  /** 서비스가 시작 시 읽는 4개 설정을 한 번에 스텁한다. */
  private void givenProps(int retentionDays, int minRetentionDays, int batchSize, int maxBatches) {
    given(properties.getRetentionDays()).willReturn(retentionDays);
    given(properties.getMinRetentionDays()).willReturn(minRetentionDays);
    given(properties.getBatchSize()).willReturn(batchSize);
    given(properties.getMaxBatchesPerRun()).willReturn(maxBatches);
  }

  @Test
  @DisplayName("한 배치로 소진되면(삭제 수 < batchSize) threshold 기준으로 삭제하고 총 삭제 수를 반환한다")
  void purge_deletes_in_single_batch() {
    // given
    givenProps(30, 7, 1000, 100);
    given(batchDeleter.deleteBatch(any(LocalDateTime.class), anyInt())).willReturn(7);

    // when
    int deleted = inboxRetentionService.purgeExpired();

    // then
    assertThat(deleted).isEqualTo(7);
    ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(batchDeleter).deleteBatch(thresholdCaptor.capture(), eq(1000));
    // threshold는 대략 now - 30일. 경계 오차를 감안해 [now-31일, now-29일] 범위인지만 확인한다.
    LocalDateTime threshold = thresholdCaptor.getValue();
    assertThat(threshold).isBefore(LocalDateTime.now().minusDays(29));
    assertThat(threshold).isAfter(LocalDateTime.now().minusDays(31));
  }

  @Test
  @DisplayName("여러 배치에 걸쳐 반복 삭제하고(각 배치 독립 트랜잭션) 마지막 부분 배치에서 종료한다")
  void purge_loops_across_batches() {
    // given: 1000, 1000, 300 → 3번째에서 batchSize 미만이라 종료
    givenProps(30, 7, 1000, 100);
    given(batchDeleter.deleteBatch(any(LocalDateTime.class), anyInt())).willReturn(1000, 1000, 300);

    // when
    int deleted = inboxRetentionService.purgeExpired();

    // then
    assertThat(deleted).isEqualTo(2300);
    verify(batchDeleter, times(3)).deleteBatch(any(LocalDateTime.class), anyInt());
  }

  @Test
  @DisplayName("1회 실행 배치 수 상한(maxBatchesPerRun)에 도달하면 중단하고, 남은 대상은 다음 주기로 넘긴다")
  void purge_stops_at_max_batches_per_run() {
    // given: 항상 batchSize를 꽉 채워 삭제(대량 누적) + 상한 2
    givenProps(30, 7, 1000, 2);
    given(batchDeleter.deleteBatch(any(LocalDateTime.class), anyInt())).willReturn(1000);

    // when
    int deleted = inboxRetentionService.purgeExpired();

    // then: 상한만큼만 삭제하고 중단(2 * 1000)
    assertThat(deleted).isEqualTo(2000);
    verify(batchDeleter, times(2)).deleteBatch(any(LocalDateTime.class), anyInt());
  }

  @Test
  @DisplayName("삭제 대상이 없으면(첫 배치가 0건) 0을 반환한다")
  void purge_returns_zero_when_nothing_deleted() {
    // given
    givenProps(30, 7, 1000, 100);
    given(batchDeleter.deleteBatch(any(LocalDateTime.class), anyInt())).willReturn(0);

    // when
    int deleted = inboxRetentionService.purgeExpired();

    // then
    assertThat(deleted).isZero();
    verify(batchDeleter).deleteBatch(any(LocalDateTime.class), anyInt());
  }

  @Test
  @DisplayName("retentionDays가 0 이하이면 전량 삭제 사고를 막기 위해 purge를 건너뛰고 0을 반환한다")
  void purge_skips_when_retention_days_non_positive() {
    // given
    givenProps(0, 7, 1000, 100);

    // when
    int deleted = inboxRetentionService.purgeExpired();

    // then
    assertThat(deleted).isZero();
    verify(batchDeleter, never()).deleteBatch(any(), anyInt());
  }

  @Test
  @DisplayName("retentionDays가 minRetentionDays(replay 윈도우)보다 짧으면 중복 재처리 위험을 막기 위해 purge를 건너뛴다")
  void purge_skips_when_below_min_retention() {
    // given: 보존 3일 < 최소 7일
    givenProps(3, 7, 1000, 100);

    // when
    int deleted = inboxRetentionService.purgeExpired();

    // then
    assertThat(deleted).isZero();
    verify(batchDeleter, never()).deleteBatch(any(), anyInt());
  }

  @Test
  @DisplayName("batchSize·maxBatchesPerRun 설정이 유효하지 않으면 purge를 건너뛴다")
  void purge_skips_when_batch_config_invalid() {
    // given: batchSize 0
    givenProps(30, 7, 0, 100);

    // when
    int deleted = inboxRetentionService.purgeExpired();

    // then
    assertThat(deleted).isZero();
    verify(batchDeleter, never()).deleteBatch(any(), anyInt());
  }
}

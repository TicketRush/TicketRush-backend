package com.ticketrush.global.inbox;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 보존 기간이 지난 {@code inbox} row를 정리하는 retention 배치(#314, #110 후속).
 *
 * <p>배선이 없으면 {@code inbox} 테이블은 무한 증가하므로, 오래된 멱등 기록을 자동 삭제해 상한을 둔다. 전역 공유 테이블이라 단일 서비스(기본:
 * booking-service)에서만 동작하도록 {@code app.inbox.retention.enabled} 조건을 건다.
 *
 * <p>{@code inbox}는 전 서비스 처리 이벤트가 쌓이는 고볼륨 테이블이라, DLT retention과 달리 <b>청크(배치) 삭제</b>로 트랜잭션·락 크기를
 * 제한한다 ({@link InboxRetentionBatchDeleter}가 배치마다 독립 트랜잭션). 이 메서드 자체는 트랜잭션을 열지 않는다. 또한 보존 기간이 Kafka
 * 재전송/replay 윈도우({@code minRetentionDays})보다 짧아지면 윈도우 내 재전달의 중복 재처리 사고로 이어지므로, 그 경우 purge를 건너뛴다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.inbox.retention", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class InboxRetentionService {

  private final InboxRetentionBatchDeleter batchDeleter;
  private final InboxRetentionProperties properties;

  public int purgeExpired() {
    int retentionDays = properties.getRetentionDays();
    int minRetentionDays = properties.getMinRetentionDays();
    int batchSize = properties.getBatchSize();
    int maxBatchesPerRun = properties.getMaxBatchesPerRun();

    if (retentionDays <= 0) {
      log.warn("Inbox retention: retentionDays({})가 0 이하여서 purge를 건너뜁니다.", retentionDays);
      return 0;
    }
    if (retentionDays < minRetentionDays) {
      // 보존 기간이 replay 윈도우보다 짧으면 윈도우 내 재전달이 중복 제거되지 않는다(정합성 사고). fail-safe로 삭제하지 않는다.
      log.error(
          "Inbox retention: retentionDays({})가 minRetentionDays({}, Kafka replay 윈도우)보다 짧아 "
              + "윈도우 내 재전달의 중복 재처리 위험이 있어 purge를 건너뜁니다. 설정을 확인하세요.",
          retentionDays,
          minRetentionDays);
      return 0;
    }
    if (batchSize <= 0 || maxBatchesPerRun <= 0) {
      log.warn(
          "Inbox retention: batchSize({})·maxBatchesPerRun({}) 설정이 유효하지 않아 purge를 건너뜁니다.",
          batchSize,
          maxBatchesPerRun);
      return 0;
    }

    LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
    int totalDeleted = 0;
    for (int batch = 0; batch < maxBatchesPerRun; batch++) {
      int deleted = batchDeleter.deleteBatch(threshold, batchSize);
      totalDeleted += deleted;
      if (deleted < batchSize) {
        // 남은 대상을 모두 소진했다(정상 종료).
        if (totalDeleted > 0) {
          log.info("Inbox retention: inbox {}건 삭제 (threshold={})", totalDeleted, threshold);
        }
        return totalDeleted;
      }
    }

    // 1회 실행 상한 도달: 남은 대상은 다음 주기에 계속 정리한다(최초 활성화 시 완만한 정리).
    log.info(
        "Inbox retention: 상한(maxBatchesPerRun={}) 도달로 {}건 삭제 후 중단(나머지 다음 주기). threshold={}",
        maxBatchesPerRun,
        totalDeleted,
        threshold);
    return totalDeleted;
  }
}

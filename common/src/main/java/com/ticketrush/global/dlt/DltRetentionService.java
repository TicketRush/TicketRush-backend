package com.ticketrush.global.dlt;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보존 기간이 지난 {@code dead_letter_record} row를 정리하는 retention 배치.
 *
 * <p>유출 시 blast radius가 시간에 비례해 커지므로 오래된 실패 메시지를 자동 삭제해 상한을 둔다(#307). DLT를 소비·저장하는 단일 서비스(기본:
 * booking-service)에서만 동작하도록 {@link DeadLetterConsumer}와 같은 {@code app.dlt.monitor.enabled} 조건을
 * 재사용한다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.dlt.monitor", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class DltRetentionService {

  private final DeadLetterRecordRepository repository;
  private final DltMonitorProperties properties;

  @Transactional
  public int purgeExpired() {
    int retentionDays = properties.getRetentionDays();
    if (retentionDays <= 0) {
      log.warn("DLT retention: retentionDays({})가 0 이하여서 purge를 건너뜁니다.", retentionDays);
      return 0;
    }
    LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
    int deleted = repository.deleteCreatedBefore(threshold);
    if (deleted > 0) {
      log.info("DLT retention: dead_letter_record {}건 삭제 (threshold={})", deleted, threshold);
    }
    return deleted;
  }
}

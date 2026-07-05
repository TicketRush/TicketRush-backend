package com.ticketrush.global.inbox;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Inbox retention 설정(#314).
 *
 * <p>{@code inbox} 테이블은 전 서비스가 공유하는 전역 단일 테이블이므로, <b>딱 한 서비스</b>(기본: booking-service)에서만 {@code
 * enabled=true}로 켜 오래된 row를 대표로 정리한다. 나머지 서비스는 기본 비활성이라 retention이 동작하지 않는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.inbox.retention")
public class InboxRetentionProperties {

  private boolean enabled = false;

  // inbox row를 이 기간 이후 retention 배치가 삭제한다. Kafka 재전송/replay 윈도우보다 길게 설정해 윈도우 내 재전달도 계속 중복 제거되게 한다.
  private int retentionDays = 30;

  // 보존 하한(일) = Kafka 재전송/replay 윈도우. retentionDays가 이보다 짧으면 윈도우 내 재전달이 중복 제거되지 않아, 안전을 위해 purge를
  // 건너뛴다.
  private int minRetentionDays = 7;

  // 청크 삭제 배치 크기. 대용량 단일 트랜잭션 DELETE를 피해 배치마다 독립 트랜잭션으로 삭제한다.
  private int batchSize = 1000;

  // 1회 실행당 배치 수 상한. 최초 활성화 시 누적분을 여러 주기에 걸쳐 완만히 정리하고, 단일 실행이 스케줄러 락(lockAtMostFor)을 넘기지 않게 한다.
  private int maxBatchesPerRun = 100;
}

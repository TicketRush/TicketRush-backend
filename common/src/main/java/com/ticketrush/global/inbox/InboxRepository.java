package com.ticketrush.global.inbox;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@link InboxEntity}에 대한 JPA 저장소. */
public interface InboxRepository extends JpaRepository<InboxEntity, Long> {

  /**
   * 해당 컨슈머 그룹이 이미 처리한 {@code eventId}인지 확인한다(멱등 fast-path).
   *
   * <p>동시 중복 수신 시에는 두 스레드가 모두 {@code false}를 받을 수 있으나, 저장 시점의 {@code (consumer_group, event_id)}
   * unique 제약이 하나만 커밋되도록 보장한다.
   */
  boolean existsByConsumerGroupAndEventId(String consumerGroup, String eventId);

  /**
   * retention 대상 row를 벌크 삭제한다. {@code threshold} 이전에 저장(createdAt)된 row만 지운다.
   *
   * <p>스케줄러/서비스 배선은 후속 작업이다({@code DltRetentionService} 패턴 참고). 이 메서드와 {@code idx_inbox_created_at}
   * 인덱스는 그 배선을 위해 미리 준비해 둔 것으로, 배선 전까지 {@code inbox} 테이블은 무한 증가할 수 있다.
   */
  @Modifying(clearAutomatically = true)
  @Query("DELETE FROM InboxEntity i WHERE i.createdAt < :threshold")
  int deleteCreatedBefore(@Param("threshold") LocalDateTime threshold);

  /**
   * retention 대상 row를 {@code batchSize}개까지만 삭제한다(청크 삭제).
   *
   * <p>{@code inbox}는 전 서비스의 처리 이벤트가 쌓이는 고볼륨 테이블이라, 단일 트랜잭션 대용량 DELETE는 긴 락·큰 undo/binlog·리플리케이션
   * 지연을 유발한다. {@code InboxRetentionService}는 이 메서드를 배치 단위로 반복 호출(각 배치는 독립 트랜잭션)해 락/트랜잭션 크기를 제한한다.
   * MySQL의 {@code DELETE ... LIMIT}을 사용한다.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      value = "DELETE FROM inbox WHERE created_at < :threshold LIMIT :batchSize",
      nativeQuery = true)
  int deleteCreatedBeforeInBatch(
      @Param("threshold") LocalDateTime threshold, @Param("batchSize") int batchSize);
}

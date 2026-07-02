package com.ticketrush.global.outbox;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link OutboxEntity}에 대한 JPA 저장소.
 *
 * <p>발행 시 Outbox row를 저장하는 {@code save()}(#101), relay 폴링용 미발송/실패 row 조회(#102), 발행 완료 row의
 * retention 삭제(#103)를 제공한다.
 */
public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

  /**
   * relay 폴링 대상 row를 오래된 순(PK 오름차순)으로 조회한다.
   *
   * <p>여러 서비스가 같은 outbox 테이블을 공유하므로, 호출 서비스는 자기 소유 {@code aggregateTypes}만 넘겨 자신의 이벤트만 발행한다. {@code
   * statuses}에는 보통 {@link OutboxStatus#PENDING}(미발송)과 {@link OutboxStatus#FAILED}(재시도 대상)를 넘기고,
   * {@code pageable}로 배치 크기를 제한한다.
   */
  List<OutboxEntity> findByAggregateTypeInAndStatusInOrderByIdAsc(
      Collection<String> aggregateTypes, Collection<OutboxStatus> statuses, Pageable pageable);

  /**
   * retention 대상 row를 벌크 삭제한다. 자기 소유 {@code aggregateTypes} 중 {@code status}(보통 {@link
   * OutboxStatus#SENT})이면서 {@code threshold} 이전에 발행된 row만 지운다.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "DELETE FROM OutboxEntity o "
          + "WHERE o.aggregateType IN :aggregateTypes AND o.status = :status "
          + "AND o.publishedAt < :threshold")
  int deleteSentBefore(
      @Param("aggregateTypes") Collection<String> aggregateTypes,
      @Param("status") OutboxStatus status,
      @Param("threshold") LocalDateTime threshold);
}

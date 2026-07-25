package com.ticketrush.global.outbox;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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
   * relay 폴링 대상 row를 (aggregateType, status) 조합 하나에 대해 오래된 순(PK 오름차순)으로 조회한다.
   *
   * <p>여러 서비스가 같은 outbox 테이블을 공유하므로, 호출 서비스는 자기 소유 aggregateType만 넘겨 자신의 이벤트만 발행한다. status는 보통
   * {@link OutboxStatus#PENDING}(미발송)과 {@link OutboxStatus#FAILED}(재시도 대상)이고, {@code limit}으로 배치
   * 크기를 제한한다. 조합 병합은 {@link OutboxRelayService}가 한다.
   *
   * <p><b>조합별 등치 조회 + {@code FORCE INDEX}인 이유</b>(#483). 등치로 쪼개야 {@code
   * idx_outbox_aggtype_status_id(aggregate_type, status, outbox_id)}의 정렬 순서가 그대로 ORDER BY가 된다 —
   * {@code IN} 한 방이면 범위가 조합 수만큼 갈라져 인덱스 순서를 못 쓴다. 다만 쪼개는 것만으로는 부족했다. {@code
   * idx_outbox_aggtype_status_published}가 {@code (aggregate_type, status)} prefix를 공유해서, 힌트가 없으면
   * 옵티마이저가 정렬 회피 이득을 비용에 반영하지 않고 그쪽을 골라 filesort가 남는다(로컬 52,100행 실측: 1,000행 스캔 + filesort 1.5ms →
   * FORCE INDEX 시 100행 0.2ms). 인덱스 열 순서를 바꿔도 같았다.
   *
   * <p><b>배포 순서 주의.</b> {@code FORCE INDEX}는 인덱스가 없으면 무시가 아니라 에러다. prod는 {@code ddl-auto:
   * validate}라 인덱스가 자동 생성되지 않으므로, 이 코드를 배포하기 <b>전에</b> 인덱스를 먼저 적용해야 한다. 순서를 어기면 릴레이 조회가 전면 실패한다.
   */
  @Query(
      value =
          "SELECT * FROM outbox FORCE INDEX (idx_outbox_aggtype_status_id) "
              + "WHERE aggregate_type = :aggregateType AND status = :status "
              + "ORDER BY outbox_id ASC LIMIT :limit",
      nativeQuery = true)
  List<OutboxEntity> findOldestRelayTargets(
      @Param("aggregateType") String aggregateType,
      @Param("status") String status,
      @Param("limit") int limit);

  /**
   * relay 폴링 대상(자기 소유 {@code aggregateTypes} 중 {@code statuses})의 적체 건수를 센다(#335 {@code
   * OUTBOX_BACKLOG} Gauge용).
   */
  long countByAggregateTypeInAndStatusIn(
      Collection<String> aggregateTypes, Collection<OutboxStatus> statuses);

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

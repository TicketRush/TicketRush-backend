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
   * <p><b>조합별 등치 조회 + 인덱스 힌트인 이유</b>(#483). 등치로 쪼개야 {@code
   * idx_outbox_aggtype_status_id(aggregate_type, status, outbox_id)}의 정렬 순서가 그대로 ORDER BY가 된다 —
   * {@code IN} 한 방이면 범위가 조합 수만큼 갈라져 인덱스 순서를 못 쓴다. 다만 쪼개는 것만으로는 부족했다. {@code
   * idx_outbox_aggtype_status_published}가 {@code (aggregate_type, status)} prefix를 공유해서, 힌트가 없으면
   * 옵티마이저가 정렬 회피 이득을 비용에 반영하지 않고 그쪽을 골라 filesort가 남는다(로컬 52,100행 실측: 998행 스캔 + filesort 1.17ms → 힌트
   * 적용 시 100행 0.14ms). 인덱스 열 순서를 바꿔도 같았다.
   *
   * <p><b>{@code FORCE INDEX}가 아니라 {@code INDEX()} 옵티마이저 힌트를 쓰는 이유</b>는 인덱스가 없을 때의 거동이 다르기 때문이다.
   * {@code FORCE INDEX}는 {@code ERROR 1176}으로 조회 자체를 실패시켜 이벤트 발행을 전면 정지시키지만, 옵티마이저 힌트는 경고(3128) 후
   * 무시돼 힌트 없던 시절의 계획으로 되돌아갈 뿐이다. prod는 {@code ddl-auto: validate}라 인덱스가 자동 생성되지 않고 수동 적용이 필요한데(자세히는
   * {@link OutboxEntity} javadoc), 그 적용이 늦어도 성능이 되돌아갈 뿐 장애가 되지 않아야 한다.
   */
  @Query(
      value =
          "SELECT /*+ INDEX(outbox idx_outbox_aggtype_status_id) */ * FROM outbox "
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

package com.ticketrush.boundedcontext.performance.out.repository;

import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceRepository
    extends JpaRepository<Performance, Long>, PerformanceRepositoryCustom {

  @EntityGraph(attributePaths = {"imageGalleryUrls", "facilities"})
  Optional<Performance> findDetailById(Long id);

  /**
   * 예매 오픈 시각이 도래한 공연을 벌크 전환한다.
   *
   * <p>벌크 JPQL은 {@code @SQLRestriction}이 적용되지 않으므로 deletedAt 조건을 명시한다. WHERE의 from 상태 가드는 어드민 수동
   * 전환이 먼저 커밋된 경우(예: CANCELED)의 lost update를 막는다. 역방향 — 어드민 PATCH가 UPCOMING으로 로드한 사이 벌크가 ON_SALE 커밋
   * — 은 {@code Performance}의 {@code @DynamicUpdate}(#459)가 막는다. PATCH가 바꾸지 않은 performanceStatus는
   * SET 절에 실리지 않으므로 stale 값이 전환을 덮지 않는다. 그전에는 다음 주기 self-heal에 기대고 있었다.
   *
   * <p>{@code clearAutomatically = true}가 호출 트랜잭션의 영속성 컨텍스트 전체를 비우므로, 엔티티를 로드하는 다른 트랜잭션에서 재사용하지 말고
   * 스케줄러 전용으로만 호출해야 한다.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE Performance p SET p.performanceStatus = :to, p.updatedAt = :now "
          + "WHERE p.performanceStatus = :from "
          + "AND p.bookingOpenAt IS NOT NULL AND p.bookingOpenAt <= :now "
          + "AND p.deletedAt IS NULL")
  int bulkTransitionStatusByBookingOpenAtDue(
      @Param("from") PerformanceStatus from,
      @Param("to") PerformanceStatus to,
      @Param("now") LocalDateTime now);

  /**
   * 예매 오픈 시각만 해제해 스케줄러 자동 전환 대상에서 제외한다.
   *
   * <p>엔티티 더티체킹 대신 타깃 UPDATE를 쓴다. 도입 당시 이유는 전체 컬럼 UPDATE였다 — 엔티티를 로드해 해제하면 로드 이후 스케줄러가 커밋한 ON_SALE
   * 전환을 stale한 UPCOMING으로 덮어쓰고, 같은 UPDATE로 bookingOpenAt이 NULL이 되어 벌크 전환 쿼리의 {@code IS NOT NULL}
   * 조건에서 영구 이탈하므로 다음 주기 self-heal조차 불가능해졌다. {@code @DynamicUpdate}(#459) 이후로는 더티체킹으로도 안전해졌지만,
   * {@code Performance.update()}가 null을 "수정 안 함"으로 해석해 해제를 표현할 수 없으므로 타깃 UPDATE를 유지한다.
   *
   * <p>벌크 JPQL은 {@code @SQLRestriction}과 Auditing이 적용되지 않으므로 deletedAt 조건과 updatedAt을 명시한다. 영향 행 수가
   * 0이면 대상 공연이 없거나 이미 소프트 삭제된 경우다.
   *
   * <p><b>역방향 경합 창은 해소됐다(#459).</b> 예전에는 엔티티를 로드하는 PATCH·상태 변경 UseCase가 전체 컬럼 UPDATE를 내보내, 그들이
   * bookingOpenAt을 로드한 뒤 이 해제가 커밋되면 마지막 커밋이 해제된 값을 되살렸다. 지금은 {@code Performance}의
   * {@code @DynamicUpdate}가 bookingOpenAt을 SET 절에서 빼므로 부활하지 않는다.
   *
   * <p>단 PATCH가 bookingOpenAt을 <b>명시로 실어 보내면</b> 해제와 같은 컬럼을 다투므로 커밋 순서대로 last-write-wins다. 이건 stale
   * 부활이 아니라 어드민의 의도적 쓰기라 방어 대상에서 제외했다.
   *
   * <p>{@code clearAutomatically = true}가 호출 트랜잭션의 영속성 컨텍스트 전체를 비우고 {@code flushAutomatically}는 기본값
   * false이므로, 같은 트랜잭션에 flush되지 않은 더티 엔티티가 있으면 조용히 유실된다. 엔티티를 함께 다루는 트랜잭션에서 호출하지 말아야 한다.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE Performance p SET p.bookingOpenAt = null, p.updatedAt = :now "
          + "WHERE p.id = :id AND p.deletedAt IS NULL")
  int clearBookingOpenAt(@Param("id") Long id, @Param("now") LocalDateTime now);
}

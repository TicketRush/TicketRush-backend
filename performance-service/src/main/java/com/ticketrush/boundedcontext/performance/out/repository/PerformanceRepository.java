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
   * 전환이 먼저 커밋된 경우(예: CANCELED)의 lost update를 막는다. 단 역방향 — 어드민 PATCH가 UPCOMING으로 로드한 사이 벌크가 ON_SALE
   * 커밋 — 은 {@code @Version} 부재로 stale 값이 덮어쓸 수 있으나, 스케줄러가 10초 주기로 재실행되며 다음 주기에 self-heal 된다.
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
   * <p>엔티티 더티체킹 대신 타깃 UPDATE를 쓰는 이유는 {@code @Version} 부재 때문이다. 엔티티를 로드해 해제하면 전체 컬럼 UPDATE가 나가면서, 로드
   * 이후 스케줄러가 커밋한 ON_SALE 전환을 stale한 UPCOMING으로 덮어쓴다. 게다가 같은 UPDATE로 bookingOpenAt이 NULL이 되어 벌크 전환
   * 쿼리의 {@code IS NOT NULL} 조건에서 영구 이탈하므로, 다른 경로와 달리 다음 주기 self-heal조차 불가능해진다.
   *
   * <p>벌크 JPQL은 {@code @SQLRestriction}과 Auditing이 적용되지 않으므로 deletedAt 조건과 updatedAt을 명시한다. 영향 행 수가
   * 0이면 대상 공연이 없거나 이미 소프트 삭제된 경우다.
   *
   * <p><b>역방향 경합 창이 남아 있다.</b> 이 쿼리는 stale write를 하지 않지만, 해제 결과가 다른 경로로부터 보호되지는 않는다. 엔티티를 로드하는
   * PATCH·상태 변경 UseCase는 {@code @Version}/{@code @DynamicUpdate} 부재로 여전히 전체 컬럼 UPDATE를 내보내므로, 그들이
   * bookingOpenAt을 로드한 뒤 이 해제가 커밋되면 마지막 커밋이 해제된 값을 되살린다. 이 경우 공연이 어드민 모르게 자동 전환 대상으로 복귀하며 판단 근거가
   * DB에 남지 않아 self-heal도 불가능하다. 근본 해결({@code @DynamicUpdate} 도입)은 엔티티 전역 영향이라 별도 이슈로 분리했다.
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

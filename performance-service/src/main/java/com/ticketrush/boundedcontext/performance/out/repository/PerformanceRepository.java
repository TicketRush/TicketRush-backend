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
}

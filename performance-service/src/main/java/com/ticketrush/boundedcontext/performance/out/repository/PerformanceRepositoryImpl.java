package com.ticketrush.boundedcontext.performance.out.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.entity.QPerformance;
import com.ticketrush.boundedcontext.performance.domain.policy.ShowTimeCutoff;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PerformanceRepositoryImpl implements PerformanceRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public Slice<Performance> findByFilters(
      Genre genre,
      Long minPrice,
      Long maxPrice,
      PerformanceStatus status,
      Long cursorId,
      int size,
      ShowTimeCutoff cutoff) {

    QPerformance performance = QPerformance.performance;

    BooleanBuilder predicate = new BooleanBuilder();

    /*
     * 공연 시작 시각이 아직 지나지 않은 공연만 (#651). 정각은 "지남"이라 showTime은 gt다.
     *
     * 두 컬럼을 CONCAT·TIMESTAMP 같은 함수로 합쳐 비교하지 않는다 — 함수는 H2(MySQL 모드)와 MySQL이 갈릴 수 있지만
     * 날짜·시각 컬럼의 비교 연산자는 양쪽에서 같은 결과를 냈다. 같은 이유로 CLOSED 벌크 전환 JPQL도 같은 꼴로 쓴다.
     * 이 조건은 그 JPQL(showDate < :today OR (showDate = :today AND showTime <= :nowTime))의 정확한 여집합이다.
     */
    predicate.and(
        performance
            .showDate
            .gt(cutoff.date())
            .or(
                performance
                    .showDate
                    .eq(cutoff.date())
                    .and(performance.showTime.gt(cutoff.time()))));

    if (genre != null) {
      predicate.and(performance.genre.eq(genre));
    }
    if (minPrice != null) {
      predicate.and(performance.price.goe(minPrice));
    }
    if (maxPrice != null) {
      predicate.and(performance.price.loe(maxPrice));
    }
    if (status != null) {
      predicate.and(performance.performanceStatus.eq(status));
    }
    if (cursorId != null) {
      predicate.and(performance.id.lt(cursorId));
    }

    // size + 1건을 조회해 다음 페이지 존재 여부를 count 쿼리 없이 판단한다
    List<Performance> rows =
        queryFactory
            .selectFrom(performance)
            .where(predicate)
            .orderBy(performance.id.desc())
            .limit(size + 1L)
            .fetch();

    boolean hasNext = rows.size() > size;
    List<Performance> content = hasNext ? rows.subList(0, size) : rows;

    // 커서 방식에서 page 번호는 무의미하므로 0으로 고정 (size 전달용)
    return new SliceImpl<>(content, PageRequest.of(0, size), hasNext);
  }
}

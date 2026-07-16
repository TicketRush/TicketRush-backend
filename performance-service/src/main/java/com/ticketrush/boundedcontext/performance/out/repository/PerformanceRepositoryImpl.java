package com.ticketrush.boundedcontext.performance.out.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.entity.QPerformance;
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

  /**
   * 커서 기반 공연 목록 조회. size는 양수를 전제한다 — 정규화(기본값·상한 캡)는 웹 계층의 {@code CursorPageRequest} 컴팩트 생성자가 책임진다.
   */
  @Override
  public Slice<Performance> findByFilters(
      Genre genre,
      Long minPrice,
      Long maxPrice,
      PerformanceStatus status,
      Long cursorId,
      int size) {

    QPerformance performance = QPerformance.performance;

    BooleanBuilder predicate = new BooleanBuilder();
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

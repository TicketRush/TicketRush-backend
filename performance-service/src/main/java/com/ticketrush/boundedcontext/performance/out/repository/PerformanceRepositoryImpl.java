package com.ticketrush.boundedcontext.performance.out.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.entity.QPerformance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PerformanceRepositoryImpl implements PerformanceRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public Page<Performance> findByFilters(
      Genre genre, Long minPrice, Long maxPrice, PerformanceStatus status, Pageable pageable) {

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

    List<Performance> content =
        queryFactory
            .selectFrom(performance)
            .where(predicate)
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .orderBy(toOrderSpecifiers(performance, pageable))
            .fetch();

    Long totalCount =
        queryFactory.select(performance.count()).from(performance).where(predicate).fetchOne();

    return new PageImpl<>(content, pageable, totalCount != null ? totalCount : 0L);
  }

  private OrderSpecifier<?>[] toOrderSpecifiers(QPerformance performance, Pageable pageable) {
    if (!pageable.getSort().isSorted()) {
      return new OrderSpecifier[] {performance.createdAt.desc()};
    }

    return pageable.getSort().stream()
        .map(order -> toOrderSpecifier(performance, order))
        .toArray(OrderSpecifier[]::new);
  }

  private OrderSpecifier<?> toOrderSpecifier(QPerformance performance, Sort.Order order) {
    Order direction = order.isAscending() ? Order.ASC : Order.DESC;
    return switch (order.getProperty()) {
      case "createdAt" -> new OrderSpecifier<>(direction, performance.createdAt);
      case "price" -> new OrderSpecifier<>(direction, performance.price);
      case "title" -> new OrderSpecifier<>(direction, performance.title);
      case "showDate" -> new OrderSpecifier<>(direction, performance.showDate);
      default -> throw new BusinessException(ErrorStatus.PERFORMANCE_INVALID_SORT_PROPERTY);
    };
  }
}

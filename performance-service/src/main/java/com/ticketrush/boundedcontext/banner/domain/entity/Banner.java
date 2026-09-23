package com.ticketrush.boundedcontext.banner.domain.entity;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import com.ticketrush.global.status.ErrorStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

/**
 * 메인 화면 상단 캐러셀에 노출할 공연 정보.
 *
 * <p>공연 제목, 소개, 날짜, 대표 이미지는 연결된 공연에서 조회한다. 배너에는 공연 연결 정보, 배너 전용 소제목, 노출 순서만 저장한다.
 *
 * <p>배너는 최대 3개까지 등록할 수 있으며, {@code displayOrder}는 1부터 3까지의 값을 가진다.
 */
@Entity
@Table(
    name = "banner",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_banner_performance", columnNames = "performance_id"),
      @UniqueConstraint(name = "uk_banner_display_order", columnNames = "display_order")
    })
@Check(constraints = "display_order BETWEEN 1 AND 3")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "banner_id"))
public class Banner extends AutoIdBaseEntity {

  /** 배너에 노출할 공연 ID. */
  @Column(name = "performance_id", nullable = false)
  private Long performanceId;

  /** 배너에서만 사용하는 선택 입력값. */
  @Column(length = 200)
  private String subtitle;

  /** 캐러셀 노출 순서. 1부터 3까지만 사용한다. */
  @Column(name = "display_order", nullable = false)
  private Integer displayOrder;

  @Builder
  public Banner(Long performanceId, String subtitle, Integer displayOrder) {
    validatePerformanceId(performanceId);
    validateDisplayOrder(displayOrder);

    this.performanceId = performanceId;
    this.subtitle = normalizeSubtitle(subtitle);
    this.displayOrder = displayOrder;
  }

  public void updateSubtitle(String subtitle) {
    this.subtitle = normalizeSubtitle(subtitle);
  }

  public void changeDisplayOrder(Integer displayOrder) {
    validateDisplayOrder(displayOrder);
    this.displayOrder = displayOrder;
  }

  private static void validatePerformanceId(Long performanceId) {
    if (performanceId == null) {
      throw new BusinessException(ErrorStatus.BANNER_ESSENTIAL_ID);
    }
  }

  private static void validateDisplayOrder(Integer displayOrder) {
    if (displayOrder == null || displayOrder < 1 || displayOrder > 3) {
      throw new BusinessException(ErrorStatus.BANNER_EXPOSURE_LIMIT);
    }
  }

  private static String normalizeSubtitle(String subtitle) {
    if (subtitle == null || subtitle.isBlank()) {
      return null;
    }

    return subtitle.trim();
  }
}

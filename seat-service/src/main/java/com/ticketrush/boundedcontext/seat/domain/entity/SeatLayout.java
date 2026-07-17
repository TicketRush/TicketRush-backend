package com.ticketrush.boundedcontext.seat.domain.entity;

import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "seat_layout",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_seat_layout_performance_id", columnNames = "performance_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "seat_layout_id"))
public class SeatLayout extends AutoIdBaseEntity {

  @Column(name = "performance_id", nullable = false)
  private Long performanceId;

  @Column(nullable = false)
  private Integer totalRows; // 총 행의 개수 (예: 10)

  @Column(nullable = false)
  private Integer maxCols; // 열의 최대 개수 (예: 12)

  @Builder
  public SeatLayout(Long performanceId, Integer totalRows, Integer maxCols) {
    this.performanceId = performanceId;
    this.totalRows = totalRows;
    this.maxCols = maxCols;
  }
}

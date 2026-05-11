package com.ticketrush.boundedcontext.seat.domain.entity;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.SeatStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "seat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "seat_id"))
public class Seat extends AutoIdBaseEntity {

  @Column(nullable = false)
  private Long seatLayoutId;

  @Column(nullable = false)
  private Long performanceId;

  @Column(nullable = false, length = 10)
  private String seatNumber;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SeatStatus seatStatus;

  @Column(name = "hold_expired_at")
  private LocalDateTime holdExpiredAt;

  @Builder
  public Seat(
      Long seatLayoutId,
      Long performanceId,
      String seatNumber,
      SeatStatus seatStatus,
      LocalDateTime holdExpiredAt) {
    this.seatLayoutId = seatLayoutId;
    this.performanceId = performanceId;
    this.seatNumber = seatNumber;
    this.seatStatus = seatStatus;
    this.holdExpiredAt = holdExpiredAt;
  }

  public void hold(LocalDateTime expiredAt) {
    // 1. 상태 검증
    if (this.seatStatus != SeatStatus.AVAILABLE) {
      throw new BusinessException(ErrorStatus.SEAT_NOT_AVAILABLE);
    }

    // 2. 시간 유효성 검증
    if (expiredAt == null || expiredAt.isBefore(LocalDateTime.now())) {
      throw new BusinessException(ErrorStatus.SEAT_HOLD_TIME_INVALID);
    }

    // 3. 상태 업데이트
    this.seatStatus = SeatStatus.HOLD;
    this.holdExpiredAt = expiredAt;
  }

  public void releaseHold() {
    if (this.seatStatus == SeatStatus.HOLD) {
      this.seatStatus = SeatStatus.AVAILABLE;
      this.holdExpiredAt = null;
    }
  }
}

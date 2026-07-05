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

  @Column(name = "booking_number", length = 50)
  private String bookingNumber;

  @Builder
  public Seat(
      Long seatLayoutId,
      Long performanceId,
      String seatNumber,
      SeatStatus seatStatus,
      LocalDateTime holdExpiredAt,
      String bookingNumber) {
    this.seatLayoutId = seatLayoutId;
    this.performanceId = performanceId;
    this.seatNumber = seatNumber;
    this.seatStatus = seatStatus;
    this.holdExpiredAt = holdExpiredAt;
    this.bookingNumber = bookingNumber;
  }

  public boolean isAvailable() {
    return this.seatStatus == SeatStatus.AVAILABLE;
  }

  public void hold(LocalDateTime expiredAt, String bookingNumber) {
    // 1. 상태 검증
    if (this.seatStatus != SeatStatus.AVAILABLE) {
      throw new BusinessException(ErrorStatus.SEAT_NOT_AVAILABLE);
    }

    // 2. 예매 번호 유효성 검증
    if (bookingNumber == null || bookingNumber.isBlank()) {
      throw new BusinessException(ErrorStatus.SEAT_BOOKING_NUMBER_REQUIRED);
    }

    // 3. 시간 유효성 검증
    if (expiredAt == null || expiredAt.isBefore(LocalDateTime.now())) {
      throw new BusinessException(ErrorStatus.SEAT_HOLD_TIME_INVALID);
    }

    // 4. 상태 업데이트
    this.seatStatus = SeatStatus.HOLD;
    this.holdExpiredAt = expiredAt;
    this.bookingNumber = bookingNumber;
  }

  public void releaseHold() {
    if (this.seatStatus == SeatStatus.HOLD) {
      this.seatStatus = SeatStatus.AVAILABLE;
      this.holdExpiredAt = null;
      this.bookingNumber = null;
    }
  }

  public void releaseBooking() {
    if (this.seatStatus == SeatStatus.HOLD || this.seatStatus == SeatStatus.SOLD) {
      this.seatStatus = SeatStatus.AVAILABLE;
      this.holdExpiredAt = null;
      this.bookingNumber = null;
    }
  }
}

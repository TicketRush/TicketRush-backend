package com.ticketrush.boundedcontext.booking.domain.entity;

import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import com.ticketrush.global.status.ErrorStatus;
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
@Table(name = "booking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "booking_id"))
public class Booking extends AutoIdBaseEntity {

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "performance_id", nullable = false)
  private Long performanceId;

  @Column(nullable = false)
  private Long seatId;

  @Column(name = "booking_number", length = 50, nullable = false, unique = true)
  private String bookingNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "booking_status", length = 20, nullable = false)
  private BookingStatus bookingStatus;

  @Column(name = "confirmed_at")
  private LocalDateTime confirmedAt;

  @Builder
  public Booking(
      String bookingNumber,
      Long userId,
      Long performanceId,
      Long seatId,
      BookingStatus bookingStatus) {
    this.userId = userId;
    this.performanceId = performanceId;
    this.seatId = seatId;
    this.bookingNumber = bookingNumber;
    this.bookingStatus = bookingStatus;
  }

  public void cancelPendingPayment() {
    cancelIfStatus(BookingStatus.PENDING);
  }

  public void cancelConfirmedByUser() {
    cancelIfStatus(BookingStatus.CONFIRMED);
  }

  private void cancelIfStatus(BookingStatus allowedStatus) {
    if (this.bookingStatus != allowedStatus) {
      throw new BusinessException(ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED);
    }
    this.bookingStatus = BookingStatus.CANCELED;
  }

  public void confirm(LocalDateTime confirmedAt) {
    if (this.bookingStatus == BookingStatus.CONFIRMED) {
      if (this.confirmedAt == null) {
        // 결제 확정 이벤트 재처리 시 과거 확정 데이터의 누락된 확정 시각을 보정한다.
        this.confirmedAt = confirmedAt;
      }
      return;
    }

    if (this.bookingStatus == BookingStatus.EXPIRED) {
      throw new BusinessException(ErrorStatus.BOOKING_EXPIRED);
    }

    if (this.bookingStatus != BookingStatus.PENDING) {
      throw new BusinessException(ErrorStatus.BOOKING_CONFIRM_NOT_ALLOWED);
    }

    this.bookingStatus = BookingStatus.CONFIRMED;
    this.confirmedAt = confirmedAt;
  }

  /**
   * 환불 성공(결제 취소) 시 예매를 REFUNDED로 종결한다 (#49).
   *
   * <p>이미 REFUNDED면 멱등 처리(전이 없이 {@code true}). CONFIRMED/REFUNDING에서만 REFUNDED로 전이한다. 그 외
   * 상태(CANCELED/PENDING/EXPIRED)는 교차 경로/비정상이므로 전이하지 않고 {@code false}를 반환한다(예외는 던지지 않아 호출 측이 ack
   * 하도록).
   *
   * @return 종결(또는 이미 종결)됐으면 {@code true}, 종결 불가 상태라 전이하지 않았으면 {@code false}
   */
  public boolean markRefunded() {
    if (this.bookingStatus == BookingStatus.REFUNDED) {
      return true;
    }
    if (this.bookingStatus == BookingStatus.CONFIRMED
        || this.bookingStatus == BookingStatus.REFUNDING) {
      this.bookingStatus = BookingStatus.REFUNDED;
      return true;
    }
    return false;
  }
}

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

  /**
   * 사용자가 결제 완료(CONFIRMED) 예매를 취소해 환불을 요청한다 → REFUNDING으로 전환한다 (#91).
   *
   * <p>좌석 반환·예매 종결(REFUNDED)은 환불 성공 이벤트({@code PaymentCanceledEvent})에만 매달아 refund-first 정합을
   * 지킨다(환불도 못 받고 좌석만 잃는 역방향 공백 차단). 환불이 최종 실패하면 {@code RefundFailedEvent}로 {@link
   * #markRefundFailed()}에서 REFUND_FAILED로 보상한다.
   */
  public void requestRefund() {
    if (this.bookingStatus != BookingStatus.CONFIRMED) {
      throw new BusinessException(ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED);
    }
    this.bookingStatus = BookingStatus.REFUNDING;
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

  /**
   * PG 환불 최종 실패 시 예매를 REFUND_FAILED로 보상 확정한다 (#91).
   *
   * <p>이미 REFUND_FAILED면 멱등 처리(전이 없이 {@code true}). REFUNDING에서만 REFUND_FAILED로 전이한다. 그 외
   * 상태(REFUNDED로 이미 종결됐거나 CONFIRMED/CANCELED 등 교차 경로)는 전이하지 않고 {@code false}를 반환한다(예외를 던지지 않아 호출 측이
   * ack 하도록).
   *
   * @return 보상(또는 이미 보상)됐으면 {@code true}, 전이 불가 상태라 전이하지 않았으면 {@code false}
   */
  public boolean markRefundFailed() {
    if (this.bookingStatus == BookingStatus.REFUND_FAILED) {
      return true;
    }
    if (this.bookingStatus == BookingStatus.REFUNDING) {
      this.bookingStatus = BookingStatus.REFUND_FAILED;
      return true;
    }
    return false;
  }
}

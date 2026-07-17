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
import jakarta.persistence.Version;
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

  /* 마지막으로 PG 환불이 실패한 시각. null이면 환불 실패 이력이 없다. 실패 사유는 payment의 refund 테이블이 SSOT다 (#391). */
  @Column(name = "refund_failed_at")
  private LocalDateTime refundFailedAt;

  /*
   * 낙관적 락 (#397). requestRefund()가 check-then-act라 사용자 취소와 관리자 재환불이 동시에 검증을 통과하면
   * RefundRequestedEvent가 이중 발행된다. 이벤트 발행은 afterCommit이므로 늦은 커밋이 버전 충돌로 실패하면 이벤트도 나가지 않는다.
   * 단, expirePendingBookingById 벌크 UPDATE는 version을 증가시키지도 검사하지도 않는다 — PENDING 전용이라 환불
   * 경로(CONFIRMED/REFUNDING)와는 겹치지 않지만, confirm-vs-expire 경합은 이 락의 보호 밖이다(기존 한계, ADR 0005).
   *
   * columnDefinition으로 DEFAULT 0을 명시하는 이유는 스키마 스냅샷 때문이다(#433). Hibernate는 DEFAULT를 만들지
   * 않는데, version을 명시하지 않는 시딩 SQL은 DEFAULT가 없으면 ERROR 1364로 깨진다. 매핑에 넣지 않으면 스냅샷을
   * 재생성할 때마다 사람이 수동으로 넣어줘야 하고, validate CI는 DEFAULT 차이를 검출하지 못해 빠뜨려도 조용히
   * 통과한다. 새 @Version 엔티티도 같은 방식으로 선언한다.
   */
  @Version
  @Column(nullable = false, columnDefinition = "bigint NOT NULL DEFAULT 0")
  private Long version;

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
   * #recordRefundFailure(LocalDateTime)}에서 CONFIRMED로 복원한다.
   *
   * <p>과거 환불에 실패한 예매({@code refundFailedAt != null})도 CONFIRMED이므로 이 경로로 다시 환불을 요청할 수 있다. 재요청은 흡수
   * 상태를 만들지 않는다 — 또 실패해도 CONFIRMED로 돌아온다 (#391).
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
   * PG 환불 최종 실패 시 예매를 CONFIRMED로 복원하고 실패 시각을 기록한다 (#391).
   *
   * <p>환불 실패는 취소가 성사되지 않았다는 뜻이다. 돈은 지불된 채고(payment=COMPLETED) 좌석은 SOLD, 티켓은 ISSUED로 남아 있으므로 예매는 여전히
   * 유효하다 — 즉 CONFIRMED다. 실패를 별도 상태로 두면 벗어나는 전이가 없는 흡수 상태가 되어 재환불도 입장도 막힌다. 실패 사실은 {@code
   * refundFailedAt}으로만 남기고, 사유는 payment의 refund 테이블(FAILED 이력)이 SSOT다.
   *
   * <p>이미 복원된 예매(CONFIRMED이고 {@code refundFailedAt}이 있음)에 이벤트가 재전달되면 멱등 처리한다(전이 없이 {@code true}).
   * REFUNDING이 아닌 그 외 상태(REFUNDED로 이미 종결됐거나 CANCELED/PENDING 등 교차 경로)는 전이하지 않고 {@code false}를
   * 반환한다(예외를 던지지 않아 호출 측이 ack 하도록).
   *
   * <p>REFUNDING이더라도 {@code failedAt}이 이미 기록된 {@code refundFailedAt}보다 나중일 때만 복원한다. Inbox
   * retention이 만료된 뒤 옛 {@code RefundFailedEvent}가 재생돼 <b>진행 중인 재환불 시도</b> 도중 도착하면, 그 시도를 조용히 중단시키고
   * stale 시각을 덮어쓰기 때문이다.
   *
   * @param failedAt PG 환불이 최종 실패한 시각
   * @return 복원(또는 이미 복원)됐으면 {@code true}, 전이 불가 상태라 전이하지 않았으면 {@code false}
   */
  public boolean recordRefundFailure(LocalDateTime failedAt) {
    if (this.bookingStatus == BookingStatus.REFUNDING) {
      if (this.refundFailedAt != null && !failedAt.isAfter(this.refundFailedAt)) {
        // 진행 중인 재환불 시도보다 앞선 실패 이벤트의 재생이다. 현재 시도를 중단시키지 않는다.
        return false;
      }
      this.bookingStatus = BookingStatus.CONFIRMED;
      this.refundFailedAt = failedAt;
      return true;
    }
    if (this.bookingStatus == BookingStatus.CONFIRMED && this.refundFailedAt != null) {
      return true;
    }
    return false;
  }

  /**
   * REFUNDING에서 {@code cutoff} 이전부터 멈춰 있는 고착 상태인지 판별한다 (#397).
   *
   * <p>payment의 PG 통신 실패(성공 여부 불명)는 재시도 소진 후 DLT로 빠지고 종결 이벤트({@code PaymentCanceledEvent}/{@code
   * RefundFailedEvent})가 끝내 오지 않는다. 이때 예매는 REFUNDING에 영구히 머문다 — 돈은 못 돌려받고 좌석은 SOLD로 묶이고 입장도 막힌다.
   * REFUNDING 진입 이후 이 엔티티는 갱신되지 않으므로 {@code updatedAt}이 곧 진입 시각이다.
   *
   * @param cutoff 고착 판정 기준 시각(현재 시각 - 임계). {@code updatedAt}이 이보다 이전이면 고착이다
   */
  public boolean isStuckInRefunding(LocalDateTime cutoff) {
    return this.bookingStatus == BookingStatus.REFUNDING
        && getUpdatedAt() != null
        && getUpdatedAt().isBefore(cutoff);
  }
}

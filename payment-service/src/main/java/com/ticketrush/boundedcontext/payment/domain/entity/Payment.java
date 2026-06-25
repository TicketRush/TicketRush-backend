package com.ticketrush.boundedcontext.payment.domain.entity;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
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
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "payment_id"))
public class Payment extends AutoIdBaseEntity {

  @Column(nullable = false)
  private Long bookingId;

  /* userId는 #207 시점 신규 도입. 기존 결제 row 호환을 위해 nullable. backfill 후 NOT NULL 전환 예정. */
  private Long userId;

  /* seatId는 #22(환불) 시점 신규 도입. 환불 시 PaymentCanceledEvent로 좌석 복귀를 전파하기 위해 confirm 시 저장한다. */
  private Long seatId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PaymentProvider provider; // 결제 수단 (예: KAKAO, NAVER)

  @Column(nullable = false)
  private Long amount; // 결제 금액

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PaymentStatus status; // 결제 상태 (PENDING, COMPLETED 등)

  @Column(length = 200)
  private String paymentKey; // PG사 발급 결제 키

  @Column(length = 100)
  private String approvalNumber; // PG사 응답 승인 번호 / 거래 식별자

  private LocalDateTime paidAt; // 결제 완료 시점

  @Builder
  private Payment(
      Long bookingId,
      Long userId,
      Long seatId,
      PaymentProvider provider,
      Long amount,
      PaymentStatus status,
      String paymentKey,
      String approvalNumber,
      LocalDateTime paidAt) {
    this.bookingId = bookingId;
    this.userId = userId;
    this.seatId = seatId;
    this.provider = provider;
    this.amount = amount;
    this.status = status;
    this.paymentKey = paymentKey;
    this.approvalNumber = approvalNumber;
    this.paidAt = paidAt;
  }

  /** 환불 처리로 결제를 취소 상태로 전이한다. */
  public void markCanceled() {
    this.status = PaymentStatus.CANCELED;
  }
}

package com.ticketrush.boundedcontext.payment.domain.entity;

import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
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
@Table(name = "refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "refund_id"))
public class Refund extends AutoIdBaseEntity {

  /* paymentId는 #22에서 환불-결제 매핑 및 멱등성 보장을 위해 도입. */
  @Column(nullable = false)
  private Long paymentId;

  @Column(nullable = false)
  private Long bookingId;

  @Column(nullable = false)
  private Long price; // 환불 금액

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20)
  private RefundStatus status; // 환불 상태 (PENDING, COMPLETED 등)

  @Column(length = 200)
  private String pgRefundKey; // PG사 환불 거래 식별자

  @Column(length = 255)
  private String reason; // 환불 사유

  private LocalDateTime requestedAt; // 환불 요청 시점

  private LocalDateTime confirmedAt; // 환불 확정 시점

  @Builder
  private Refund(
      Long paymentId,
      Long bookingId,
      Long price,
      RefundStatus status,
      String pgRefundKey,
      String reason,
      LocalDateTime requestedAt,
      LocalDateTime confirmedAt) {
    this.paymentId = paymentId;
    this.bookingId = bookingId;
    this.price = price;
    this.status = status;
    this.pgRefundKey = pgRefundKey;
    this.reason = reason;
    this.requestedAt = requestedAt;
    this.confirmedAt = confirmedAt;
  }
}

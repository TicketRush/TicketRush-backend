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

  /* paymentId는 #22에서 환불-결제 매핑에 사용. unique 제약으로 동일 결제의 중복 환불을 DB 레벨에서 멱등 보장한다. */
  @Column(nullable = false, unique = true)
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

  /** PG 환불이 성공해 확정(COMPLETED)된 환불 이력을 만든다. */
  public static Refund completed(
      Long paymentId,
      Long bookingId,
      Long price,
      String pgRefundKey,
      String reason,
      LocalDateTime requestedAt,
      LocalDateTime confirmedAt) {
    return Refund.builder()
        .paymentId(paymentId)
        .bookingId(bookingId)
        .price(price)
        .status(RefundStatus.COMPLETED)
        .pgRefundKey(pgRefundKey)
        .reason(reason)
        .requestedAt(requestedAt)
        .confirmedAt(confirmedAt)
        .build();
  }

  /**
   * PG 환불이 거절돼 실패(FAILED)로 확정된 환불 이력을 만든다(#334).
   *
   * <p>{@code pgRefundKey}·{@code confirmedAt}은 확정된 환불이 아니므로 비운다. PG 원본 거절 코드/사유는 취소 클라이언트가 아직 포착하지
   * 않아 담지 않는다(#332의 취소판은 후속). {@code reason}에는 내부 실패 사유({@code ErrorStatus} 메시지)만 남긴다.
   */
  public static Refund failed(
      Long paymentId, Long bookingId, Long price, String reason, LocalDateTime requestedAt) {
    return Refund.builder()
        .paymentId(paymentId)
        .bookingId(bookingId)
        .price(price)
        .status(RefundStatus.FAILED)
        .reason(reason)
        .requestedAt(requestedAt)
        .build();
  }

  /**
   * 실패(FAILED)한 환불을 재시도해 성공하면 COMPLETED로 전이한다(#334).
   *
   * <p>FAILED 상태에서만 전이할 수 있고 그 외 상태에서 호출되면 도메인 불변식 위반이므로 {@link IllegalStateException}을 던진다({@link
   * Payment#markCanceled()}와 동일한 방어선). PG 환불 식별자·확정 시각과 함께 사유도 성공 사유로 갱신해 status와의 모순(실패 사유 잔존)을
   * 없앤다.
   *
   * <p>재시도 성공 시 이미 존재하는 FAILED row의 unique(payment_id) 슬롯을 재사용하기 위해 {@link
   * PaymentCancelPersister#persist}가 새 INSERT 대신 이 전이를 사용한다. 관리자 수동 복구 배선은 후속이다.
   */
  public void markCompleted(String pgRefundKey, String reason, LocalDateTime confirmedAt) {
    if (this.status != RefundStatus.FAILED) {
      throw new IllegalStateException("환불 완료 전이는 FAILED 상태에서만 가능합니다. 현재 상태=" + this.status);
    }
    this.status = RefundStatus.COMPLETED;
    this.pgRefundKey = pgRefundKey;
    this.reason = reason;
    this.confirmedAt = confirmedAt;
  }
}

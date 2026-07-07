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

  /* paymentKey는 PG사가 결제 건마다 유일하게 발급한다. confirm 중복 요청·webhook 재수신이 동일 결제를 중복
   * 생성하지 못하도록 unique 제약을 둔다. (멱등성 최종 방어선, #90) NULL은 MySQL에서 중복으로 보지 않는다. */
  @Column(length = 200, unique = true)
  private String paymentKey; // PG사 발급 결제 키

  @Column(length = 100)
  private String approvalNumber; // PG사 응답 승인 번호 / 거래 식별자

  private LocalDateTime paidAt; // 결제 완료 시점

  /* 결제 실패(FAILED) 시 실패 코드/사유를 남겨 추적·집계·CS 대응에 쓴다. 성공 결제는 NULL이다(#297). failureCode는
   * ErrorStatus code(예: PAYMENT_400_003) 문자열이다. */
  @Column(length = 50)
  private String failureCode; // 실패 코드 (ErrorStatus code)

  @Column(length = 255)
  private String failureReason; // 실패 사유 (사람이 읽는 메시지)

  /* PG(예: Toss)가 4xx로 내려준 원본 거절 코드/사유. 내부 failureCode(카테고리)와 별개로 원본 사유를 남겨 CS·정밀
   * 분석에 쓴다(#332). 성공 결제·원본 미확보(에러 body 파싱 실패) 시 NULL이다. */
  @Column(length = 50)
  private String pgFailureCode; // PG 원본 거절 코드

  @Column(length = 255)
  private String pgFailureReason; // PG 원본 거절 사유

  /* completedBookingId는 status=COMPLETED일 때만 booking_id 값을 갖는 DB generated 컬럼이다(그 외 NULL). 이 컬럼의
   * unique 제약(uk_payment_completed_booking)으로 동일 booking에 서로 다른 paymentKey를 가진 confirm이 동시에 들어와도
   * COMPLETED 결제가 2건 생성되지 못하게 DB 레벨에서 막는다(#296, TOCTOU 최종 방어선). MySQL은 NULL을 unique 중복으로 보지 않으므로
   * CANCELED/FAILED 후 재결제는 허용된다. 값 계산과 unique 제약은 수동 DDL로만 관리하고(ddl-auto=update가 generated 컬럼을
   * 만들지 못한다) 애플리케이션은 읽기만 하므로, insertable=false·updatable=false로 매핑한다. */
  @Column(name = "completed_booking_id", insertable = false, updatable = false)
  private Long completedBookingId;

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

  /**
   * PG 거절·검증 실패 등 결제 실패가 확정된 시점의 이력을 FAILED 상태로 생성한다(#297).
   *
   * <p>실패 추적/집계·CS 대응을 위해 실패 코드·사유를 함께 남긴다. 결제가 성립하지 않았으므로 {@code paymentKey}·{@code
   * approvalNumber}·{@code paidAt}은 비운다. 특히 {@code paymentKey}를 비워 unique 제약과 충돌하지 않게 해 재결제(재시도)를
   * 막지 않는다. {@code completedBookingId}(generated)도 COMPLETED가 아니므로 NULL이라 동일 booking에 FAILED가 여러 건
   * 누적될 수 있다(재시도 이력). PG 통신 실패(결과 불명)는 고아 청구 위험이 있어 이 팩토리로 기록하지 않는다(UseCase에서 분기).
   *
   * <p>{@code pgFailureCode}/{@code pgFailureReason}는 PG 원본 거절 코드/사유로, 미확보 시 null이다(#332). 컬럼
   * 길이(50/255)를 넘는 원본이 들어와도 이력 저장(INSERT)이 깨져 #297 추적까지 유실되지 않도록 팩토리 내부에서 잘라 담는다.
   */
  public static Payment failed(
      Long bookingId,
      Long userId,
      Long seatId,
      PaymentProvider provider,
      Long amount,
      String failureCode,
      String failureReason,
      String pgFailureCode,
      String pgFailureReason) {
    Payment payment =
        Payment.builder()
            .bookingId(bookingId)
            .userId(userId)
            .seatId(seatId)
            .provider(provider)
            .amount(amount)
            .status(PaymentStatus.FAILED)
            .build();
    payment.failureCode = failureCode;
    payment.failureReason = failureReason;
    payment.pgFailureCode = truncate(pgFailureCode, 50);
    payment.pgFailureReason = truncate(pgFailureReason, 255);
    return payment;
  }

  /* 컬럼 길이 초과 원본으로 INSERT가 깨지지 않도록 방어적으로 자른다. null은 그대로 둔다. */
  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  /**
   * 환불 처리로 결제를 취소 상태로 전이한다.
   *
   * <p>COMPLETED 상태에서만 취소로 전이할 수 있다. 그 외 상태에서 호출되면 도메인 불변식 위반이므로 {@link IllegalStateException}을
   * 던진다. (정상 흐름에서는 UseCase의 상태 검증과 환불 unique 제약이 먼저 막으므로 이 가드는 방어선이다.)
   */
  public void markCanceled() {
    if (this.status != PaymentStatus.COMPLETED) {
      throw new IllegalStateException("결제 취소는 COMPLETED 상태에서만 가능합니다. 현재 상태=" + this.status);
    }
    this.status = PaymentStatus.CANCELED;
  }

  /** 이미 결제가 확정(COMPLETED)된 상태인지 여부. webhook 재수신/중복 confirm 시 멱등 판정에 사용한다. */
  public boolean isAlreadyProcessed() {
    return this.status == PaymentStatus.COMPLETED;
  }
}

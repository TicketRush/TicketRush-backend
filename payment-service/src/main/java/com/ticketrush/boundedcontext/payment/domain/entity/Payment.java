package com.ticketrush.boundedcontext.payment.domain.entity;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제. 인덱스 두 개를 두는 근거는 아래와 같다.
 *
 * <p><b>idx_payment_booking_id</b> — bookingId 조건 조회(exists/findFirst/count By BookingId And
 * Status)가 confirm hot path에서 매 요청 실행되므로 둔다(#412). 세 조회 모두 (booking_id, status) 복합 조건이지만, booking당
 * payment row가 유계(COMPLETED 최대 1건 + FAILED 상한 #333)라 booking_id 단일 인덱스로 seek하면 status는 소수 잔여 row
 * 필터라 복합 인덱스 실익이 없어 단일로 둔다.
 *
 * <p><b>idx_payment_user_id_status</b> — 내 결제 내역 조회(#463). {@code PaymentGetListUseCase}가 호출하는
 * {@code findByUserIdAndStatus(userId, COMPLETED, pageable)}는 이 인덱스가 없으면 쓸 수 있는 인덱스가 없어 payment 전체를
 * 훑는다(possible_keys=NULL). 반환이 {@code Page}라 count 쿼리까지 같은 전수 스캔을 반복하고, 스캔량이 테이블 크기에 비례해 계속 늘어난다.
 * 컬럼 순서는 둘 다 equality지만 선택도가 높은 {@code user_id}가 선두다 — {@code status}는 카디널리티 4에 이 조회에선 항상 COMPLETED
 * 단일값이라 단독 선택도가 없다. 역순 (status, user_id)은 status만으로 후보를 거의 못 줄인다. 정렬 키 {@code paid_at}을 세 번째로 붙인
 * 커버링 인덱스는 이 이슈 범위(2컬럼 측정)에서 다루지 않아 채택하지 않았다. 대가로 결제 쓰기마다 인덱스 유지 비용이 늘지만, 쓰기가 결제 건수에 한정돼 트레이드오프가
 * 유리하다.
 *
 * <p><b>기존 가동 DB에는 수동 DDL이 필요하다.</b> {@code @Table}의 {@code @Index}는 ddl-auto=update인 로컬/신규 초기화
 * DB(init SQL)에서만 생성되고, prod(validate)는 인덱스 부재를 검출하지 못한다(#296 수동 DDL 관행과 동일). schema-validate CI도
 * 인덱스 부재는 통과시키므로 스냅샷·validate만으로는 prod 반영이 보증되지 않는다. 실행 전 {@code SHOW INDEX FROM payment}로 두 인덱스의
 * 실존 여부를 먼저 확인하고, 없는 것만 골라 실행한다(신규 초기화 DB는 init SQL이 이미 만들어 Duplicate key name으로 실패한다):
 *
 * <pre>
 *   ALTER TABLE payment
 *     ADD INDEX idx_payment_booking_id (booking_id), ALGORITHM=INPLACE, LOCK=NONE;
 *   ALTER TABLE payment
 *     ADD INDEX idx_payment_user_id_status (user_id, status), ALGORITHM=INPLACE, LOCK=NONE;
 * </pre>
 */
@Entity
@Table(
    name = "payment",
    indexes = {
      @Index(name = "idx_payment_booking_id", columnList = "booking_id"),
      @Index(name = "idx_payment_user_id_status", columnList = "user_id, status")
    })
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
   * 만들지 못한다) 애플리케이션은 읽기만 하므로, insertable=false·updatable=false로 매핑한다.
   *
   * 이 수동 DDL이 스키마 스냅샷(deploy/mysql/init)에서 누락돼 신규 초기화 DB에 방어선이 빠져 있던 것을
   * 스냅샷에 복구했다(#422). 신규 초기화 DB는 init SQL이 만들어주므로, 아래 런북은 스냅샷 복구 전에
   * 초기화된 기존 DB에만 사람이 직접 실행한다. 실행 전 점검 3가지:
   *   -- ① EXTRA='STORED GENERATED'가 나오면 이미 적용된 DB다. (1)을 생략한다:
   *   SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ticket_rush'
   *     AND TABLE_NAME='payment' AND COLUMN_NAME='completed_booking_id';
   *   -- ② 인덱스가 나오면 (2)를 생략한다:
   *   SELECT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='ticket_rush'
   *     AND TABLE_NAME='payment' AND INDEX_NAME='uk_payment_completed_booking';
   *   -- ③ 결과가 있으면 (2)가 ERROR 1062로 실패한다. 중복 COMPLETED 정리(어느 건이 진짜인지 CS 판단) 후 진행:
   *   SELECT booking_id, COUNT(*) FROM payment WHERE status='COMPLETED'
   *     GROUP BY booking_id HAVING COUNT(*) > 1;
   *
   *   -- (1) 일반 컬럼 → STORED generated 전환. MySQL 8.0에서 이 방향 MODIFY는 허용되지만 ALGORITHM=COPY
   *   --     (테이블 리빌드)라 리빌드 동안 payment 쓰기가 차단된다 → 저트래픽 창에 실행.
   *   --     기존 COMPLETED row의 값은 전환 시 자동 backfill된다.
   *   ALTER TABLE payment MODIFY COLUMN completed_booking_id BIGINT
   *     GENERATED ALWAYS AS (CASE WHEN status = 'COMPLETED' THEN booking_id END) STORED;
   *   -- (2) unique 제약 추가. 보조 인덱스 생성이라 온라인(INPLACE·LOCK=NONE) 가능:
   *   ALTER TABLE payment
   *     ADD CONSTRAINT uk_payment_completed_booking UNIQUE (completed_booking_id),
   *     ALGORITHM=INPLACE, LOCK=NONE;
   *   -- 롤백: DROP INDEX uk_payment_completed_booking 후 MODIFY ... BIGINT NULL(일반 컬럼 전환, 무손실).
   *   --     단 롤백 MODIFY도 generated↔일반 전환이라 (1)과 동일하게 ALGORITHM=COPY(쓰기 차단) —
   *   --     장애 대응 중이라도 저트래픽 창에 실행한다. */
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

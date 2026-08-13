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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 예매. 인덱스 두 개를 두는 근거는 아래와 같다.
 *
 * <p><b>idx_booking_user_id_status</b> — 내 예매 목록 조회(#464). {@code BookingGetMyBookingsUseCase}가
 * 호출하는 {@code findByUserIdAndBookingStatus}는 이 인덱스가 없으면 옵티마이저가 idx_booking_status_updated_at의 선두
 * 컬럼(booking_status)만 써서 <b>CONFIRMED 전체를 훑은 뒤 user_id로 필터한다</b>. 반환이 {@code Page}라 count 쿼리까지 같은
 * 스캔을 반복한다(18,000행/CONFIRMED 12,600행 기준 요청당 15.6ms → 0.2ms, count는 커버링 인덱스가 되어 원본 테이블 접근이 사라짐). 컬럼
 * 순서는 둘 다 equality지만 선택도가 높은 {@code user_id}가 선두다. {@code created_at}을 붙인 3컬럼은 측정으로 기각했다 —
 * CONFIRMED 경로의 정렬 키가 {@code confirmed_at DESC, created_at DESC}라 filesort가 그대로 남고, 후보가 이미 사용자당 수십
 * 행으로 줄어든 뒤의 정렬이라 실익이 없다. 대가로 예매 쓰기마다 인덱스 유지 비용이 늘어난다 — 전수 스캔 2회를 없애는 값으로 수용한다.
 *
 * <p><b>idx_booking_status_updated_at</b> — 환불 복구 폴링용(ADR 0005). 스키마 스냅샷과 ADR에는 있는데 엔티티 선언이 누락돼
 * 로컬(ddl-auto=update)·H2 테스트 DB에는 생성되지 않던 drift를 #464에서 보정했다.
 *
 * <p><b>기존 가동 DB에는 수동 DDL이 필요하다.</b> {@code @Table}의 {@code @Index}는 ddl-auto=update인 로컬/신규 초기화
 * DB(init SQL)에서만 생성되고, prod(validate)는 인덱스 부재를 검출하지 못한다(#296 수동 DDL 관행과 동일). 아래는 인덱스가 없는 기존 DB에만
 * 실행한다(신규 초기화 DB는 init SQL이 이미 만들어 Duplicate key name 실패):
 *
 * <pre>
 *   ALTER TABLE booking
 *     ADD INDEX idx_booking_user_id_status (user_id, booking_status),
 *     ALGORITHM=INPLACE, LOCK=NONE;
 * </pre>
 *
 * <p>실행 전 {@code SHOW INDEX FROM booking}으로 두 인덱스의 실존 여부를 먼저 확인한다. idx_booking_status_updated_at은
 * ADR 0005에서 이미 적용된 전제라 위 DDL에 포함하지 않았지만(중복 실행 시 Duplicate key name), 이 전제 역시 검증된 적 없는 가정이므로 — 이
 * 클래스가 보정한 drift가 그랬듯 — 확인 결과 없으면 그때 같은 방식으로 함께 추가한다.
 *
 * <p><b>paid_amount 마이그레이션 (#561).</b> 결제 금액 컬럼을 추가한다. 기존 가동 DB에는 아래를 실행해야 하며, prod는 {@code
 * ddl-auto: validate}라 <b>배포 전에 실행하지 않으면 기동이 실패한다.</b>
 *
 * <pre>
 *   ALTER TABLE booking ADD COLUMN paid_amount bigint DEFAULT NULL,
 *     ALGORITHM=INSTANT;
 *
 *   -- 백필: 이미 확정된 예매의 결제 금액을 payment에서 가져온다.
 *   -- 이 조인은 단일 공유 DB(ADR 0003)에서 일회성 운영 마이그레이션에 한해 허용한다.
 *   -- 애플리케이션 코드의 크로스 도메인 조인 금지 규율과는 별개다.
 *   UPDATE booking b
 *     JOIN payment p ON p.completed_booking_id = b.booking_id
 *   SET b.paid_amount = p.amount
 *   WHERE b.booking_status IN ('CONFIRMED', 'REFUNDING', 'REFUNDED')
 *     AND b.paid_amount IS NULL;
 *
 *   -- 검증: 0이어야 한다. 0이 아니면 그만큼 매출 집계에서 빠지며,
 *   -- 통계 응답의 revenue_complete=false와 missing_amount_bookings로 드러난다.
 *   SELECT COUNT(*) FROM booking
 *     WHERE booking_status = 'CONFIRMED' AND paid_amount IS NULL;
 * </pre>
 *
 * <p>백필하지 않아도 서비스는 정상 동작한다 — 매출이 그만큼 작게 나오고 통계 응답이 그 사실을 스스로 신고한다. 조용히 틀린 값을 내지 않는 것이 이 설계의 요지다. 백필
 * 대상에 REFUNDING·REFUNDED를 포함한 것은 그 예매들도 한때 확정을 거쳤고, 환불이 실패해 CONFIRMED로 복원되면(ADR 0005) 다시 매출에 잡혀야 하기
 * 때문이다.
 */
@Entity
@Table(
    name = "booking",
    indexes = {
      @Index(name = "idx_booking_user_id_status", columnList = "user_id, booking_status"),
      @Index(name = "idx_booking_status_updated_at", columnList = "booking_status, updated_at")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "booking_id"))
public class Booking extends AutoIdBaseEntity {

  /**
   * PENDING 예매가 결제를 기다리는 시간(분). 이 시간이 지나면 {@code BookingExpireUseCase}가 EXPIRED로 전이시킨다.
   *
   * <p>화면에 내리는 마감 시각({@link #paymentExpiresAt()})과 실제 만료 판정이 같은 값을 봐야 하므로 도메인에 둔다. 둘이 갈라지면 사용자가 보는
   * 타이머와 서버의 만료가 어긋난다 (#559).
   */
  public static final int PAYMENT_WAIT_MINUTES = 5;

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
   * 확정 시점에 실제로 결제된 금액 (#561). PaymentConfirmedEvent가 싣고 오는 값을 그대로 박아 둔다.
   *
   * 이 스냅샷이 없을 때는 공연의 현재 가격(performance.price)을 빌려 썼는데, 그 값은 예매 이후 관리자가 가격을
   * 바꾸면 과거 매출까지 소급해 흔들리고, 공연이 삭제되면 아예 조회할 수 없어 매출 집계가 영구히 불가능해졌다.
   * 금액을 자기 행에 들고 있으면 관리자 통계가 크로스 서비스 호출 없이 SUM 한 번으로 끝나고, 조회 실패라는
   * 실패 모드 자체가 사라진다.
   *
   * PENDING·CANCELED·EXPIRED는 결제가 성사되지 않았으므로 null이다. 확정을 거친 예매(CONFIRMED 이후 상태)는
   * 값이 있는 것이 정상이지만, 이 컬럼 도입 이전에 확정된 행은 백필 전까지 null이다 — 매출 집계는 null을
   * 0으로 취급하지 않고 별도로 센다(BookingRepository 참고).
   */
  @Column(name = "paid_amount")
  private Long paidAmount;

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
   * PENDING 예매가 결제를 마쳐야 하는 시각. PENDING이 아니면 {@code null}이다 (#559).
   *
   * <p><b>seat의 {@code holdExpiredAt}과 값이 다르다.</b> 좌석 HOLD는 {@code BookingCreatedEvent}를 거쳐 비동기로
   * 걸리므로 seat의 만료 시각은 Kafka 소비 지연만큼 이 값보다 늦다. 그런데 예매를 실제로 만료시키는 것은 {@code BookingExpireUseCase}이고 그
   * 기준이 {@code createdAt + PAYMENT_WAIT_MINUTES}다. 즉 <b>이쪽이 실효 마감이자 보수적인 값</b>이며, seat 값을 화면에 쓰면
   * 실제보다 늦은 시각을 보여 사용자가 결제 도중 예매를 잃는다.
   */
  public LocalDateTime paymentExpiresAt() {
    if (this.bookingStatus != BookingStatus.PENDING) {
      return null;
    }
    return getCreatedAt().plusMinutes(PAYMENT_WAIT_MINUTES);
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

  /**
   * 결제 완료로 예매를 확정한다 (#49).
   *
   * <p>{@code paidAmount}는 결제 확정 이벤트가 싣고 온 <b>실제 결제 금액</b>이다. 공연의 현재 가격을 빌려 쓰는 대신 이 시점의 값을 박아 둬야 이후
   * 가격 변경이나 공연 삭제가 과거 매출을 흔들지 못한다 (#561).
   */
  public void confirm(LocalDateTime confirmedAt, Long paidAmount) {
    if (this.bookingStatus == BookingStatus.CONFIRMED) {
      if (this.confirmedAt == null) {
        // 결제 확정 이벤트 재처리 시 과거 확정 데이터의 누락된 확정 시각을 보정한다.
        this.confirmedAt = confirmedAt;
      }
      if (this.paidAmount == null) {
        // 같은 이유로 결제 금액도 보정한다 — 컬럼 도입 이전에 확정된 예매가 이벤트 재처리로 채워질 수 있다.
        this.paidAmount = paidAmount;
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
    this.paidAmount = paidAmount;
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
   * REFUNDING도 CONFIRMED도 아닌 상태(REFUNDED로 이미 종결됐거나 CANCELED/PENDING 등 교차 경로)는 전이하지 않고 {@code
   * false}를 반환한다(예외를 던지지 않아 호출 측이 ack 하도록).
   *
   * <p>REFUNDING이더라도 {@code failedAt}이 이미 기록된 {@code refundFailedAt}보다 나중일 때만 복원한다. Inbox
   * retention이 만료된 뒤 옛 {@code RefundFailedEvent}가 재생돼 <b>진행 중인 재환불 시도</b> 도중 도착하면, 그 시도를 조용히 중단시키고
   * stale 시각을 덮어쓰기 때문이다.
   *
   * <p><b>REFUNDING을 거치지 않은 CONFIRMED에도 실패를 기록한다 (#492).</b> 좌석 확정 실패 보상은 사용자 취소와 달리 예매 상태를 바꾸지 않고
   * 곧바로 환불을 걸므로(refund-first, ADR 0005), 그 환불이 PG에 거절되면 CONFIRMED이면서 {@code refundFailedAt}이 없는 채로
   * 실패가 도착한다. 이 분기가 없으면 {@code false}로 떨어져 실패가 기록되지 않고, 그러면 미해결 목록({@code CONFIRMED +
   * refundFailedAt IS NOT NULL})에도 잡히지 않을뿐더러 관리자 재환불 API가 {@code BOOKING_REFUND_RETRY_NOT_ALLOWED}로
   * 거부해 <b>수동 복구 수단까지 함께 막힌다</b>. 과금이 남은 건에서 그건 가장 필요한 도구다.
   *
   * @param failedAt PG 환불이 최종 실패한 시각
   * @return 복원(또는 이미 복원)됐거나 실패를 기록했으면 {@code true}, 전이 불가 상태면 {@code false}
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
    if (this.bookingStatus == BookingStatus.CONFIRMED) {
      if (this.refundFailedAt == null) {
        // 보상 환불(#492)의 실패다. 이미 CONFIRMED라 전이는 없고 실패 사실만 남긴다.
        this.refundFailedAt = failedAt;
      }
      // 이미 기록이 있으면 갱신하지 않는다 — 복원 시점이 곧 미해결 진입 시각이고, 재전달로 밀리면 안 된다.
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

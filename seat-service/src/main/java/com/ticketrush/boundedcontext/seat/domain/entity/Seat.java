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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 좌석. 인덱스 두 개를 두는 근거는 아래와 같다.
 *
 * <p><b>idx_seat_performance_id</b> — {@code SeatRepository.findSeatMapByPerformanceId}가
 * performanceId로만 필터한다. 인덱스가 없으면 공연당 수천 행을 매 요청 풀스캔해 부하 테스트 수치가 앱이 아닌 인덱스 부재를 반영한다.
 *
 * <p><b>idx_seat_status_hold_expired_at</b> — 만료 HOLD 조회가 주기 스케줄러의 핫패스다(#343). {@code
 * SeatStatusScheduler}(60초)의 {@code findExpiredHoldSeats}와 {@code SeatHeldGaugeMetrics}(30초)의
 * {@code countHeldSeats}가 모두 {@code seat_status} + {@code hold_expired_at} 조건을 쓰는데, 인덱스가 없으면 <b>만료
 * 좌석이 0건이어도 매 tick마다 테이블 전체를 스캔한다</b>(좌석 30만 기준 335ms → 0.03ms). 컬럼 순서는 equality({@code
 * seat_status})가 선두, range({@code hold_expired_at})가 후미다. seat_status는 카디널리티가 3이라 단독 선택도는 낮지만,
 * range 컬럼을 선두에 두면 뒤 컬럼을 seek에 쓰지 못한다. 대가로 좌석 전이(HOLD/확정/해제) 쓰기가 약 20% 느려진다 — 상시 반복되는 풀스캔을 없애는 값으로
 * 수용한다.
 *
 * <p><b>기존 가동 DB에는 수동 DDL이 필요하다.</b> {@code @Table}의 {@code @Index}는 ddl-auto=update인 로컬/신규 초기화
 * DB(init SQL)에서만 생성되고, prod(validate)는 인덱스 부재를 검출하지 못한다(#296 수동 DDL 관행과 동일). 아래는 인덱스가 없는 기존 DB에만
 * 실행한다(신규 초기화 DB는 init SQL이 이미 만들어 Duplicate key name 실패):
 *
 * <pre>
 *   ALTER TABLE seat
 *     ADD INDEX idx_seat_status_hold_expired_at (seat_status, hold_expired_at),
 *     ALGORITHM=INPLACE, LOCK=NONE;
 * </pre>
 *
 * <p><b>version 컬럼도 기존 가동 DB에 수동 추가해야 한다(#427).</b> 인덱스와 달리 컬럼은 prod의 ddl-auto=validate가 검출하므로, 배포
 * <b>전</b>에 실행하지 않으면 기동이 실패한다. {@code DEFAULT 0}이 기존 행을 백필한다({@code booking.version}과 동일 — ADR 0005
 * 선례).
 *
 * <pre>
 *   ALTER TABLE seat ADD COLUMN version bigint NOT NULL DEFAULT 0;
 * </pre>
 */
@Entity
@Table(
    name = "seat",
    indexes = {
      @Index(name = "idx_seat_performance_id", columnList = "performance_id"),
      @Index(name = "idx_seat_status_hold_expired_at", columnList = "seat_status, hold_expired_at")
    })
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

  /*
   * 낙관적 락 (#427). SeatHoldUseCase가 read-check-write(findById → isAvailable → hold)라 두 트랜잭션이 같은 좌석을
   * 동시에 읽으면 둘 다 검증을 통과해 HOLD를 쓸 수 있다. 지금 이걸 막는 건 Redisson 락뿐인데, Redis는 fsync everysec이라
   * 크래시 시 락 키가 유실될 수 있다(#426). 정합성의 최종 방어선은 DB여야 한다 — Redis 락은 경쟁을 앞단에서 걸러내는 성능
   * 최적화로 남는다.
   *
   * 단, SeatRepository의 벌크 UPDATE(confirmSoldById, releaseExpiredHoldById)는 version을 증가시키지도 검사하지도
   * 않는다. 그래도 안전한 이유는 가드가 원자적이어서가 아니라 <b>상태 집합이 겹치지 않기 때문이다.</b> 두 벌크는 HOLD 행에만
   * 발화하는데(WHERE seatStatus = HOLD), DB에 UPDATE를 내보내는 더티 체킹 경로는 둘뿐이고 둘 다 HOLD에서 시작하지 않는다.
   *
   *   - SeatHoldUseCase → hold() : AVAILABLE에서만 시작한다(hold()가 AVAILABLE 아니면 예외).
   *   - SeatReleaseSoldSeatUseCase → releaseBooking() : SOLD에서만 시작한다. releaseBooking() 자체는 HOLD도
   *     받지만, 호출부의 SOLD 가드가 HOLD를 먼저 스킵한다.
   *
   * releaseHold()를 부르는 두 곳(SeatReleaseSingleUseCase, SeatReleaseExpiredChunkProcessor)은 예외처럼 보이지만
   * @Modifying(clearAutomatically = true) 직후라 엔티티가 detach 상태다 — DB에 나가지 않는 in-memory 조정이다.
   *
   * 즉 HOLD 행을 들고 더티 체킹으로 쓰는 트랜잭션이 없어 벌크와 경합할 수 없고, 어떤 행이 HOLD로 진입하려면 반드시
   * hold()(version 증가)를 거치므로 stale 엔티티는 낙관적 락에 걸린다.
   *
   * 이 불변식이 깨지면(= HOLD 좌석을 더티 체킹으로 고치는 UseCase를 추가하거나, 위 두 곳의 clearAutomatically를 끄거나,
   * AVAILABLE/SOLD 행에 발화하는 벌크 UPDATE를 추가하면) stale 엔티티가 충돌 없이 덮어써 버전 검사가 조용히 무력화된다.
   * 그때는 JPQL UPDATE VERSIONED를 검토한다(booking도 같은 한계를 안고 있다 — ADR 0005).
   *
   * columnDefinition으로 DEFAULT 0을 명시하는 이유는 스키마 스냅샷 때문이다(#433). Hibernate는 DEFAULT를 만들지
   * 않는데, version을 명시하지 않는 시딩 SQL(load-test/seed/seed_load.sql 등)은 DEFAULT가 없으면 ERROR 1364로
   * 깨진다. 매핑에 넣지 않으면 스냅샷을 재생성할 때마다 사람이 수동으로 DEFAULT를 넣어줘야 하고, validate CI는
   * DEFAULT 차이를 검출하지 못해 빠뜨려도 조용히 통과한다. 새 @Version 엔티티도 같은 방식으로 선언한다.
   */
  @Version
  @Column(nullable = false, columnDefinition = "bigint NOT NULL DEFAULT 0")
  private Long version;

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

  /**
   * 이 예매 번호로 이미 SOLD인가 (#489).
   *
   * <p>판매 확정 재수신(멱등 성공)을 "그 예매의 좌석이 아님"(과금 후 좌석 없음)과 가르는 판정이다. {@code
   * SeatRepository.confirmSoldById}가 SOLD 전이에서 {@code booking_number}를 지우지 않기 때문에 성립한다 — 지우도록 바꾸면 이
   * 판정이 조용히 전부 false가 되어 정상 중복까지 409로 뒤집힌다.
   */
  public boolean isSoldTo(String bookingNumber) {
    return this.seatStatus == SeatStatus.SOLD
        && this.bookingNumber != null
        && this.bookingNumber.equals(bookingNumber);
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

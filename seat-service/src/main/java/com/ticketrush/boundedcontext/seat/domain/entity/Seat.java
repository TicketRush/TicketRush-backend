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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 좌석. 인덱스 두 개를 두는 근거는 아래와 같다.
 *
 * <p><b>idx_seat_performance_id</b> — {@code SeatRepository.findSeatLayoutsByPerformanceId}가
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

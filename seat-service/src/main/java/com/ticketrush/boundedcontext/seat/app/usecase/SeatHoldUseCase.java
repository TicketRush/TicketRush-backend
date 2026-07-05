package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class SeatHoldUseCase {

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;

  /**
   * 좌석을 HOLD로 전이한다.
   *
   * <p>이미 선점/판매된(미가용) 좌석은 예외를 던지지 않고 {@code false}를 반환한다. 이 유스케이스는 상위 {@code runIfFirst} 트랜잭션에
   * 조인되므로, 미가용을 예외로 알리면 트랜잭션이 rollback-only가 되어 같은 트랜잭션에서 보상 이벤트(Outbox INSERT)를 발행할 수 없다. 따라서 결정적
   * 실패인 "미가용"은 반환값으로 알려 호출부가 보상 이벤트를 원자적으로 발행하게 하고, 일시(인프라) 오류만 예외로 전파한다.
   *
   * @return HOLD로 전이했으면 {@code true}, 이미 선점/판매되어 전이하지 못했으면 {@code false}
   */
  public boolean execute(Long seatId, LocalDateTime holdExpiredAt, String bookingNumber) {
    Seat seat =
        seatRepository
            .findById(seatId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.SEAT_NOT_FOUND));

    if (!seat.isAvailable()) {
      return false;
    }

    seat.hold(holdExpiredAt, bookingNumber);
    seatStatusEventPublisher.publishAfterCommit(seat);
    return true;
  }
}

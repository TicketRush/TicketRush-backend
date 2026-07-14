package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Transactional
@RequiredArgsConstructor
public class SeatHoldUseCase {

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;
  private final MeterRegistry meterRegistry;

  /**
   * 좌석을 HOLD로 전이한다.
   *
   * <p>이미 선점/판매된(미가용) 좌석은 예외를 던지지 않고 {@code false}를 반환한다. 이 유스케이스는 상위 {@code runIfFirst} 트랜잭션에
   * 조인되므로, 미가용을 예외로 알리면 트랜잭션이 rollback-only가 되어 같은 트랜잭션에서 보상 이벤트(Outbox INSERT)를 발행할 수 없다. 따라서 결정적
   * 실패인 "미가용"은 반환값으로 알려 호출부가 보상 이벤트를 원자적으로 발행하게 하고, 일시(인프라) 오류만 예외로 전파한다.
   *
   * <p><b>단, {@code true}를 반환해도 HOLD가 확정된 것은 아니다(#427).</b> {@code Seat.version} 낙관적 락 충돌은 이 메서드가
   * 아니라 <b>상위 트랜잭션의 커밋(flush) 시점</b>에 {@code ObjectOptimisticLockingFailureException}으로 터진다 — Redis
   * 락이 유실돼 두 처리가 같은 좌석을 동시에 읽은 경우다. 그러면 {@code SeatFacade}의 catch를 우회해 트랜잭션 전체(HOLD·Inbox·Outbox)가
   * 롤백되고, 리스너에서 일시 오류로 분류돼({@code BusinessException}이 아니므로) Kafka 재시도로 돌아온다. 재시도 때는 좌석이 이미 HOLD라 이
   * 메서드가 {@code false}를 반환해 보상 이벤트가 정상 발행된다. 즉 충돌은 재시도로 수렴하며, 중복 선점은 DB가 최종 차단한다.
   *
   * @return HOLD로 전이했으면 {@code true}, 이미 선점/판매되어 전이하지 못했으면 {@code false}
   */
  public boolean execute(Long seatId, LocalDateTime holdExpiredAt, String bookingNumber) {
    Seat seat =
        seatRepository
            .findById(seatId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.SEAT_NOT_FOUND));

    if (!seat.isAvailable()) {
      Counter.builder(MetricNames.SEAT_HOLD)
          .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_UNAVAILABLE)
          .register(meterRegistry)
          .increment();
      return false;
    }

    seat.hold(holdExpiredAt, bookingNumber);
    seatStatusEventPublisher.publishAfterCommit(seat);
    incrementSuccessAfterCommit();
    return true;
  }

  /**
   * 성공 카운터를 커밋 이후에 올린다(#427).
   *
   * <p>낙관적 락 충돌은 커밋 시점에 터지므로, 여기서 즉시 증가시키면 <b>롤백된 HOLD도 success로 집계된다.</b> 게다가 재시도된 같은 메시지가
   * unavailable을 한 번 더 올려 한 건이 두 번 잡힌다. 충돌 발생률을 관측해야 할 지표가 충돌을 성공으로 보여주면 안 되므로, {@code
   * SeatStatusEventPublisher.publishAfterCommit}과 같은 방식으로 커밋 이후에만 센다. 미가용(unavailable)은 트랜잭션이 깨끗한
   * 채로 보상 이벤트와 함께 커밋되므로 즉시 증가시켜도 무방하다.
   */
  private void incrementSuccessAfterCommit() {
    Counter counter =
        Counter.builder(MetricNames.SEAT_HOLD)
            .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_SUCCESS)
            .register(meterRegistry);

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      counter.increment();
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            counter.increment();
          }
        });
  }
}

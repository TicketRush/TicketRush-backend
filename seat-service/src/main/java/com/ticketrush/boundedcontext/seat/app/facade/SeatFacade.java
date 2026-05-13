package com.ticketrush.boundedcontext.seat.app.facade;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAvailabilityResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatConfirmSoldUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatGetAvailabilityUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatGetSeatLayoutsUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatHoldUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatLockUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatUnlockUseCase;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.shared.seat.event.SeatHoldFailedEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatFacade {

  private final SeatGetAvailabilityUseCase seatGetAvailabilityUseCase;
  private final SeatGetSeatLayoutsUseCase seatGetSeatLayoutsUseCase;
  private final SeatConfirmSoldUseCase seatConfirmSoldUseCase;
  private final SeatHoldUseCase seatHoldUseCase;
  private final SeatLockUseCase seatLockUseCase;
  private final SeatUnlockUseCase seatUnlockUseCase;
  private final EventPublisher eventPublisher;

  public List<SeatLayoutResponse> getPerformanceSeatLayouts(Long performanceId) {
    return seatGetSeatLayoutsUseCase.execute(performanceId);
  }

  public SeatAvailabilityResponse getPerformanceSeatAvailability(Long performanceId) {
    return seatGetAvailabilityUseCase.execute(performanceId);
  }

  public void holdSeat(Long seatId, LocalDateTime holdExpiredAt) {
    seatHoldUseCase.execute(seatId, holdExpiredAt);
  }

  public void confirmSold(String bookingNumber, Long seatId) {
    seatConfirmSoldUseCase.execute(bookingNumber, seatId);
  }

  public void tryLockSeat(Long bookingId, Long seatId, Long userId) {
    // 1. Redis 락 시도
    Optional<LocalDateTime> holdExpiredAtOpt = seatLockUseCase.execute(seatId, userId);

    if (holdExpiredAtOpt.isPresent()) {
      try {
        // 2-A. 성공: Seat DB 상태를 HOLD로 업데이트
        seatHoldUseCase.execute(seatId, holdExpiredAtOpt.get());

      } catch (Exception e) {
        log.error("좌석 DB 업데이트 중 오류 발생. Redis 락 해제 및 보상 이벤트 발행. seatId: {}", seatId, e);

        // 1) 획득했던 Redis 락 즉시 해제
        seatUnlockUseCase.execute(seatId);

        // 2) Booking 모듈로 보상 트랜잭션 이벤트 발행
        SeatHoldFailedEvent failedEvent =
            new SeatHoldFailedEvent(bookingId, seatId, "좌석 상태 업데이트 실패: " + e.getMessage());
        publishCompensationEvent(failedEvent);
      }
    } else {
      // 2-B. 실패: Booking 모듈로 보상 트랜잭션 이벤트 발행
      SeatHoldFailedEvent failedEvent = new SeatHoldFailedEvent(bookingId, seatId, "이미 선점된 좌석입니다.");
      publishCompensationEvent(failedEvent);
    }
  }

  /** 보상 이벤트 발행을 담당하며, 실패 시 상위 리스너로 예외를 전파합니다. */
  private void publishCompensationEvent(SeatHoldFailedEvent event) {
    try {
      eventPublisher.publish(event);
      log.info("보상 이벤트(SeatHoldFailedEvent) 정상 발행 완료. bookingId: {}", event.bookingId());
    } catch (Exception e) {
      // TODO: 추후 예매와 좌석 간의 정합성 보장을 위해 OUTBOX 패턴 도입 예정
      log.error("보상 트랜잭션 이벤트 발행에 실패했습니다. bookingId: {}", event.bookingId(), e);
      /* 이 예외가 발생하면 최상단의 BookingCreatedEventListener에서 수동 커밋(ack.acknowledge())이
        실행되지 않고, Spring Kafka의 재시도 정책을 타게 됩니다.
      */
      throw new RuntimeException("보상 이벤트 발행 실패로 인해 처리를 롤백하고 재시도합니다.", e);
    }
  }
}

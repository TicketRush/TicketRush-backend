package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatHoldExpiredPublisher;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseSingleUseCase {

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;
  private final SeatHoldExpiredPublisher seatHoldExpiredPublisher;

  @Transactional
  public void execute(Long seatId) {
    seatRepository
        .findById(seatId)
        .ifPresentOrElse(
            seat -> {
              if (seat.getSeatStatus() != SeatStatus.HOLD) {
                log.info(
                    "Redis 만료 이벤트 수신: 좌석 {} 상태가 HOLD가 아니어서 롤백을 스킵했습니다. status: {}",
                    seatId,
                    seat.getSeatStatus());
                return;
              }

              seatHoldExpiredPublisher.publish(seat);
              seat.releaseHold();
              seatStatusEventPublisher.publishAfterCommit(seat);
              log.info("Redis 만료 이벤트 수신: 좌석 {} 상태를 AVAILABLE로 즉시 롤백했습니다.", seatId);
            },
            () ->
                log.warn(
                    "Redis 만료 이벤트 수신: 대상 좌석을 DB에서 찾을 수 없습니다. (데이터 정합성 확인 필요) seatId: {}", seatId));
  }
}

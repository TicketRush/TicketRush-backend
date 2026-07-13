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

              // 위 검사는 조회 스냅샷이라, 그 사이 결제가 확정됐을 수 있다. 상태 가드가 달린 조건부 UPDATE로만 갱신한다.
              if (seatRepository.releaseHoldById(seatId, SeatStatus.HOLD, SeatStatus.AVAILABLE)
                  == 0) {
                log.info("Redis 만료 이벤트 수신: 좌석 {}이(가) 조회 이후 HOLD가 아니게 되어 롤백을 건너뜁니다.", seatId);
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

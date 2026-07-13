package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatHoldExpiredPublisher;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
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

              // 위 검사는 조회 스냅샷이라, 그 사이 결제가 확정됐거나 좌석이 다른 예매로 재선점됐을 수 있다.
              // Redis 만료 이벤트 배달은 지연될 수 있어 재선점이 드문 일이 아니다. 가드가 달린 조건부 UPDATE로만
              // 갱신한다 — bookingNumber가 조회 시점 그대로이고 여전히 만료 상태일 때만 해제된다.
              int updated =
                  seatRepository.releaseExpiredHoldById(
                      seatId,
                      seat.getBookingNumber(),
                      LocalDateTime.now(),
                      SeatStatus.HOLD,
                      SeatStatus.AVAILABLE);
              if (updated == 0) {
                log.info("Redis 만료 이벤트 수신: 좌석 {}이(가) 조회 이후 선점 상태가 바뀌어 롤백을 건너뜁니다.", seatId);
                return;
              }

              // seat는 이 시점에 detach다(clearAutomatically). releaseHold()는 DB에 나가지 않는 in-memory
              // 조정이고, 위 조건부 UPDATE가 이미 DB를 바꿨다. 가드가 통과했으므로 스냅샷은 최신이다.
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

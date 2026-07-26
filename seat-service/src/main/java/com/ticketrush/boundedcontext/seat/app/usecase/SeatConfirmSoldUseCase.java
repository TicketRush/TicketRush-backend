package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatConfirmSoldUseCase {

  private final SeatRepository seatRepository;
  private final SeatUnlockUseCase seatUnlockUseCase;
  private final SeatStatusEventPublisher seatStatusEventPublisher;

  @Transactional
  public void execute(String bookingNumber, Long seatId) {
    int updatedCount =
        seatRepository.confirmSoldById(seatId, bookingNumber, SeatStatus.HOLD, SeatStatus.SOLD);

    // UPDATE 뒤에 읽는다. @Modifying(clearAutomatically=true)로 영속성 컨텍스트가 비워져 DB 최신 상태가 온다.
    Seat seat =
        seatRepository
            .findById(seatId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.SEAT_NOT_FOUND));

    if (updatedCount == 1) {
      seatStatusEventPublisher.publishAfterCommit(seat);
      forceReleaseAfterCommit(seatId);
      log.info("좌석 판매 확정 완료. bookingNumber: {}, seatId: {}", bookingNumber, seatId);
      return;
    }

    // 이미 같은 예매로 SOLD면 결제 확정 이벤트 재수신이다. 목표 상태에 이미 도달했으므로 멱등 성공으로 돌려준다(#489).
    // 여기서 409를 내면 호출자가 "정상 중복"과 "좌석 없음"을 구분할 수 없다. SSE는 다시 쏘지 않는다 —
    // 첫 확정이 이미 발행했다. Redis 락만 다시 푼다: forceUnlock은 멱등이고, 첫 확정의 afterCommit이
    // 프로세스 종료로 유실됐을 경우의 값싼 보험이다.
    if (seat.isSoldTo(bookingNumber)) {
      seatUnlockUseCase.forceRelease(seatId);
      log.info("이미 같은 예매로 확정된 좌석(멱등 성공). bookingNumber: {}, seatId: {}", bookingNumber, seatId);
      return;
    }

    // 만료 해제로 AVAILABLE·booking_number NULL이 됐거나, 다른 예매가 쥔 좌석이다. 결제는 이미 끝났을 수
    // 있으므로 정상 중복과 뭉개지 않고 전용 409로 구분해 호출자(booking)가 보상을 걸 수 있게 한다(#489).
    // CRITICAL은 과금 사실을 아는 booking 쪽에서 찍는다 — 양쪽에서 찍으면 한 사건에 알림이 두 번 운다.
    log.warn(
        "좌석 판매 확정 실패(이 예매의 좌석이 아님). seatId: {}, 요청 bookingNumber: {}, "
            + "현재 status: {}, 현재 bookingNumber: {}",
        seatId,
        bookingNumber,
        seat.getSeatStatus(),
        seat.getBookingNumber());
    throw new BusinessException(ErrorStatus.SEAT_CONFIRM_NOT_OWNED);
  }

  private void forceReleaseAfterCommit(Long seatId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      seatUnlockUseCase.forceRelease(seatId);
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            seatUnlockUseCase.forceRelease(seatId);
          }
        });
  }
}

package com.ticketrush.boundedcontext.seat.app.usecase;

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

  @Transactional
  public void execute(String bookingNumber, Long seatId) {
    int updatedCount = seatRepository.confirmSoldById(seatId, SeatStatus.HOLD, SeatStatus.SOLD);

    if (updatedCount == 1) {
      forceReleaseAfterCommit(seatId);
      log.info("좌석 판매 확정 완료. bookingNumber: {}, seatId: {}", bookingNumber, seatId);
      return;
    }

    if (!seatRepository.existsById(seatId)) {
      throw new BusinessException(ErrorStatus.SEAT_NOT_FOUND);
    }

    throw new BusinessException(ErrorStatus.SEAT_NOT_AVAILABLE);
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

package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatConfirmSoldUseCase {

  private final SeatRepository seatRepository;

  @Transactional
  public void execute(String bookingNumber, Long seatId) {
    if (!seatRepository.existsById(seatId)) {
      throw new BusinessException(ErrorStatus.SEAT_NOT_FOUND);
    }

    int updatedCount = seatRepository.confirmSoldById(seatId, SeatStatus.HOLD, SeatStatus.SOLD);

    if (updatedCount != 1) {
      throw new BusinessException(ErrorStatus.SEAT_NOT_AVAILABLE);
    }

    log.info("좌석 판매 확정 완료. bookingNumber: {}, seatId: {}", bookingNumber, seatId);
  }
}

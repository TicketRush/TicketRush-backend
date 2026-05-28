package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.SeatStatus;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseBookedSeatUseCase {

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;

  @Transactional
  public void execute(Long seatId, String bookingNumber) {
    if (bookingNumber == null || bookingNumber.isBlank()) {
      log.warn("예매 취소 좌석 반환 스킵: 이벤트 예매 번호가 없습니다. seatId: {}", seatId);
      return;
    }

    var seat =
        seatRepository
            .findById(seatId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.SEAT_NOT_FOUND));

    if (seat.getSeatStatus() == SeatStatus.AVAILABLE) {
      log.info("예매 취소 좌석 반환 스킵: 이미 AVAILABLE 상태입니다. seatId: {}", seatId);
      return;
    }

    if (seat.getBookingNumber() == null) {
      log.warn("예매 취소 좌석 반환 스킵: 좌석의 예매 번호가 없어 이벤트 예매 번호를 검증할 수 없습니다. seatId: {}", seatId);
      return;
    }

    if (!Objects.equals(seat.getBookingNumber(), bookingNumber)) {
      log.warn(
          "예매 취소 좌석 반환 스킵: 좌석의 예매 번호가 이벤트와 다릅니다. seatId: {}, eventBookingNumber: {}",
          seatId,
          bookingNumber);
      return;
    }

    seat.releaseBooking();
    seatStatusEventPublisher.publishAfterCommit(seat);
    log.info("예매 취소 좌석 반환 완료. seatId: {}", seatId);
  }
}

package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 성공({@code PaymentCanceledEvent}) 시 결제 완료(SOLD) 좌석을 AVAILABLE로 반환한다 (#49).
 *
 * <p>환불은 결제 완료(SOLD) 좌석 대상이므로 <b>SOLD일 때만</b> 반환한다. HOLD(진행 중 선점)나 이미 AVAILABLE인 좌석은 멱등 스킵한다.
 *
 * <p><b>한계(→ #91 조율)</b>: {@code PaymentCanceledEvent}에는 예매 번호(bookingNumber)가 없어 좌석이 실제로 그 환불 예매의
 * 것인지 교차검증할 수 없다. 좌석 id 재사용 + 이벤트 지연이 겹치면 다른 예매의 좌석을 반환할 수 있으므로(ABA), payment 측 이벤트에 bookingNumber를
 * 포함해 {@code SeatReleaseBookedSeatUseCase}처럼 교차검증하도록 후속 조율이 필요하다. 현재는 SOLD 한정 가드로 blast radius를
 * 좁힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseSoldSeatUseCase {

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;

  @Transactional
  public void execute(Long seatId) {
    var seat = seatRepository.findById(seatId).orElse(null);

    if (seat == null) {
      // 결제는 취소됐으나 대상 좌석이 없다. 재전달로도 나타날 수 없으므로 예외 대신 정상 종료(멱등 기록 유지).
      log.warn("환불 좌석 반환 스킵: 좌석이 존재하지 않습니다. seatId: {}", seatId);
      return;
    }

    if (seat.getSeatStatus() != SeatStatus.SOLD) {
      // 환불 대상은 SOLD 좌석뿐이다. HOLD/AVAILABLE는 대상이 아니므로 멱등 스킵한다.
      log.info("환불 좌석 반환 스킵: SOLD 상태가 아닙니다. seatId: {}, status: {}", seatId, seat.getSeatStatus());
      return;
    }

    seat.releaseBooking(); // SOLD → AVAILABLE
    seatStatusEventPublisher.publishAfterCommit(seat);
    log.info("환불 좌석 반환 완료. seatId: {}", seatId);
  }
}

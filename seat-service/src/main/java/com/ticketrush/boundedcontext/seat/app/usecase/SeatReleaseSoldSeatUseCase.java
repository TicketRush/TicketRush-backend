package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 성공({@code PaymentCanceledEvent}) 시 결제 완료(SOLD) 좌석을 AVAILABLE로 반환한다 (#49, #91).
 *
 * <p>환불은 결제 완료(SOLD) 좌석 대상이므로 <b>SOLD일 때만</b> 반환한다. HOLD(진행 중 선점)나 이미 AVAILABLE인 좌석은 멱등 스킵한다.
 *
 * <p><b>ABA 방지(#91)</b>: 이벤트에 {@code bookingNumber}가 있으면(이벤트 기반 환불 경로) 좌석의 예매 번호와 대조해, 좌석 id 재사용 +
 * 이벤트 지연이 겹쳐 다른 예매의 좌석을 반환하는 것을 막는다. {@code bookingNumber}가 없으면(결제 취소 API #22 경로는 payment가 예매 번호를
 * 모름) SOLD 한정 가드로만 blast radius를 좁힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseSoldSeatUseCase {

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;

  @Transactional
  public void execute(Long seatId, String bookingNumber) {
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

    // 이벤트에 예매 번호가 있으면 좌석 소유를 교차검증한다(ABA 방지). 좌석의 예매 번호가 다르면 다른 예매가 재선점한 좌석이므로 반환하지 않는다.
    if (bookingNumber != null
        && !bookingNumber.isBlank()
        && !Objects.equals(seat.getBookingNumber(), bookingNumber)) {
      log.warn(
          "환불 좌석 반환 스킵: 좌석의 예매 번호가 이벤트와 다릅니다(ABA 방지). seatId: {}, eventBookingNumber: {}",
          seatId,
          bookingNumber);
      return;
    }

    seat.releaseBooking(); // SOLD → AVAILABLE
    seatStatusEventPublisher.publishAfterCommit(seat);
    log.info("환불 좌석 반환 완료. seatId: {}", seatId);
  }
}

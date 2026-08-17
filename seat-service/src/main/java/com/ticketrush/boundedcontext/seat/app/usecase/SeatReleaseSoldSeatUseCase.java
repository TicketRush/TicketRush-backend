package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatEventSource;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.config.SeatRefundReleaseProperties;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.types.SeatStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * <p><b>ABA 방지(#91)</b>: 좌석의 예매 번호를 이벤트의 값과 대조해, 그 좌석이 아직 이 예매의 것일 때만 반환한다. 좌석 id는 재사용되지 않지만 좌석의
 * <b>소유가 다른 예매로 넘어갈</b> 수는 있고, 그때 반환하면 남이 결제 완료한 자리를 빈자리로 되돌리게 된다.
 *
 * <p><b>예매 번호가 없으면 대조가 통째로 꺼진다(#608).</b> 과거에는 결제 취소 API(#22) 경로가 예매 번호를 모른 채 발행해서 이 상태를 정상으로 다뤘지만,
 * 그 fail-open이 바로 위 사고의 입구였다 — SOLD 한정 가드만으로는 "누구의 SOLD인지"를 가르지 못한다. payment가 booking 조회로 값을 채우도록
 * 고친 뒤에는 빈 값이 계약상 나오지 않으므로, 이 경우를 <b>정상 스킵이 아니라 계약 파기</b>로 다룬다.
 *
 * <p>다만 거부는 {@link SeatRefundReleaseProperties#isRequireBookingNumber()}로 감싼다. 배포 창에서 구버전 payment의
 * 이벤트가 도착할 수 있는데, 그때 거부하면 정상 취소의 좌석까지 SOLD로 고착되고 관리자 강제 해제가 SOLD를 거부해 수동 DML 외에 풀 수 없기 때문이다. <b>관측은
 * 스위치와 무관하게 항상 한다</b> — 스위치를 켜도 되는지는 {@code booking_number_missing} 집계가 0인지로 판단한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseSoldSeatUseCase {

  private static final String SKIP_SEAT_NOT_FOUND = "seat_not_found";
  private static final String SKIP_NOT_SOLD = "not_sold";
  private static final String SKIP_BOOKING_NUMBER_MISMATCH = "booking_number_mismatch";
  private static final String SKIP_BOOKING_NUMBER_MISSING = "booking_number_missing";

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;
  private final SeatRefundReleaseProperties refundReleaseProperties;
  private final MeterRegistry meterRegistry;

  @Transactional
  public void execute(Long seatId, String bookingNumber) {
    var seat = seatRepository.findById(seatId).orElse(null);

    if (seat == null) {
      // 결제는 취소됐으나 대상 좌석이 없다. 재전달로도 나타날 수 없으므로 예외 대신 정상 종료(멱등 기록 유지).
      countSkip(SKIP_SEAT_NOT_FOUND);
      log.warn("환불 좌석 반환 스킵: 좌석이 존재하지 않습니다. seatId: {}", seatId);
      return;
    }

    if (seat.getSeatStatus() != SeatStatus.SOLD) {
      // 환불 대상은 SOLD 좌석뿐이다. HOLD/AVAILABLE는 대상이 아니므로 멱등 스킵한다.
      countSkip(SKIP_NOT_SOLD);
      log.info("환불 좌석 반환 스킵: SOLD 상태가 아닙니다. seatId: {}, status: {}", seatId, seat.getSeatStatus());
      return;
    }

    if (bookingNumber == null || bookingNumber.isBlank()) {
      // 발행 측이 값을 채우도록 고친 뒤에는(#608) 도달하지 않아야 한다. 도달했다면 좌석 소유를 판정할 수
      // 없다는 뜻이고, 그대로 반환하면 다른 예매가 결제 완료한 SOLD 좌석을 빈자리로 되돌릴 수 있다.
      countSkip(SKIP_BOOKING_NUMBER_MISSING);
      if (!refundReleaseProperties.isRequireBookingNumber()) {
        // 배포 창 대비로 가드가 꺼져 있는 구간. 종전 동작(반환)을 유지하되 사실은 남긴다.
        log.error("[CRITICAL] 환불 좌석 반환 이벤트에 예매 번호가 없습니다! 가드가 꺼져 있어 반환을 진행합니다. seatId: {}", seatId);
      } else {
        log.error(
            "[CRITICAL] 예매 번호가 없어 환불 좌석 반환을 중단했습니다! 좌석이 SOLD로 남아 수동 처리가 필요합니다. seatId: {}", seatId);
        return;
      }
    } else if (!Objects.equals(seat.getBookingNumber(), bookingNumber)) {
      // 좌석의 예매 번호가 다르면 그 좌석은 이미 다른 예매의 것이다(ABA 방지). 반환하면 남의 좌석을 푼다.
      countSkip(SKIP_BOOKING_NUMBER_MISMATCH);
      log.warn(
          "환불 좌석 반환 스킵: 좌석의 예매 번호가 이벤트와 다릅니다(ABA 방지). seatId: {}, eventBookingNumber: {}",
          seatId,
          bookingNumber);
      return;
    }

    seat.releaseBooking(); // SOLD → AVAILABLE
    seatStatusEventPublisher.publishAfterCommit(seat, SeatEventSource.REFUND_RELEASE);
    log.info("환불 좌석 반환 완료. seatId: {}", seatId);
  }

  private void countSkip(String reason) {
    Counter.builder(MetricNames.SEAT_REFUND_RELEASE_SKIPPED)
        .tag(MetricNames.TAG_REASON, reason)
        .register(meterRegistry)
        .increment();
  }
}

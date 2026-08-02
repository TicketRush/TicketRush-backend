package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import com.ticketrush.shared.booking.event.RefundRequestedEvent;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자의 예매 취소. 예매 상태에 따라 전이가 갈린다.
 *
 * <ul>
 *   <li><b>PENDING</b> — 결제 전 이탈이다. 즉시 CANCELED로 종결하고 반납할 좌석 ID를 돌려준다 (#559).
 *   <li><b>CONFIRMED</b> — 곧바로 종결하지 않고 환불을 요청한다(REFUNDING). 좌석 반환·예매 종결은 환불 성공 이벤트에 매단다 (#91).
 *   <li><b>그 외</b> — {@code requestRefund()}가 {@code BOOKING_CANCEL_NOT_ALLOWED}로 거절한다.
 * </ul>
 *
 * <p><b>조회는 한 번뿐이다.</b> 상태 분기를 호출자가 하려면 booking을 먼저 읽어야 하는데, 그러면 CONFIRMED 환불 경로에 SELECT가 하나 늘어난다.
 * 분기를 이 트랜잭션 안으로 들여 기존 조회 하나로 끝낸다.
 *
 * <p><b>좌석 반납은 여기서 하지 않는다.</b> seat-service 왕복이 {@code @Transactional} 안에 들어가면 다운스트림 지연이 booking의
 * DB 커넥션 풀을 물어 예매 경로 전체를 마비시킨다({@code BookingValidateTicketNotUsedUseCase}와 같은 규율). 좌석 ID만 돌려주고
 * 호출자가 커밋 뒤에 반납한다 — 그 순서 덕분에 seat가 되쏘는 {@code SeatHoldExpiredEvent}가 도착해도 이미 CANCELED라 EXPIRED로
 * 뒤집히지 않는다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BookingCancelMyBookingUseCase {

  private final BookingRepository bookingRepository;
  private final EventPublisher eventPublisher;

  /**
   * @return PENDING을 즉시 취소했으면 반납할 좌석 ID, 환불 요청(CONFIRMED)이면 {@code Optional.empty()}
   */
  public Optional<Long> execute(Long userId, String bookingNumber) {
    Booking booking =
        bookingRepository
            .findByBookingNumberAndUserId(bookingNumber, userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    if (booking.getBookingStatus() == BookingStatus.PENDING) {
      return cancelPending(booking);
    }

    // 결제 완료 예매의 취소는 곧바로 종결하지 않고 환불을 요청한다(CONFIRMED→REFUNDING). 좌석 반환·예매 종결은 환불 성공 이벤트에 매단다(#91).
    booking.requestRefund();

    eventPublisher.publish(
        new RefundRequestedEvent(
            booking.getId(),
            booking.getBookingNumber(),
            booking.getSeatId(),
            booking.getUserId(),
            LocalDateTime.now()));

    return Optional.empty();
  }

  /**
   * 결제 전 이탈을 즉시 종결한다 (#559).
   *
   * <p><b>{@link BookingExpiredEvent}를 발행하는 이유.</b> payment가 이 이벤트로 {@code ExpiredBooking}을 적재해 결제
   * 확정을 차단한다(#224). 발행하지 않으면 사용자가 좌석을 반납한 뒤 열려 있던 결제창에서 결제를 완료해 <b>과금은 됐는데 좌석이 없는</b> 상태가 된다. 이벤트
   * 이름은 만료지만 payment에게 필요한 의미("이 예매는 더 이상 결제할 수 없다")는 취소와 동일하다.
   */
  private Optional<Long> cancelPending(Booking booking) {
    booking.cancelPendingPayment();

    eventPublisher.publish(new BookingExpiredEvent(booking.getId(), LocalDateTime.now()));

    log.info(
        "PENDING 예매를 사용자 요청으로 즉시 취소했습니다. bookingNumber: {}, bookingId: {}, seatId: {}",
        booking.getBookingNumber(),
        booking.getId(),
        booking.getSeatId());

    return Optional.of(booking.getSeatId());
  }
}

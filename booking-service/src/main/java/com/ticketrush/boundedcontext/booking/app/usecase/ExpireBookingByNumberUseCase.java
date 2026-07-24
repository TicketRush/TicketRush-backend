package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 hold 만료 이벤트를 받아 대상 예매를 EXPIRED로 전이한다.
 *
 * <p>seat-service가 보유한 식별자는 {@code bookingNumber}뿐이므로, id를 해소한 뒤 기존 {@code
 * expirePendingBookingById}(조건부 UPDATE)를 재사용한다. 전이가 실제로 일어난 경우에만({@code updated == 1}) {@link
 * BookingExpiredEvent}를 재발행해 payment 다운스트림(만료 예매 가드)과의 정합성을 유지한다.
 *
 * <p>멱등성: {@code WHERE bookingStatus = PENDING} 가드로 이미 EXPIRED/CONFIRMED/CANCELED인 예매는 no-op이 된다.
 * 현재 유일한 호출부인 리스너의 {@code InboxService.runIfFirst(...)} 트랜잭션에 조인(REQUIRED)하며, outbox 발행이 활성 트랜잭션을
 * 요구하므로 향후 트랜잭션 없는 호출부가 추가될 때를 대비해 방어적으로 {@code @Transactional}을 부착한다(#471).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ExpireBookingByNumberUseCase {

  private final BookingRepository bookingRepository;
  private final EventPublisher eventPublisher;

  public void execute(String bookingNumber, LocalDateTime expiredAt) {
    Long bookingId = bookingRepository.findIdByBookingNumber(bookingNumber).orElse(null);
    if (bookingId == null) {
      log.warn("좌석 hold 만료 이벤트 수신: 대상 예매를 찾을 수 없어 스킵합니다. bookingNumber: {}", bookingNumber);
      return;
    }

    int updated =
        bookingRepository.expirePendingBookingById(
            bookingId, BookingStatus.PENDING, BookingStatus.EXPIRED);

    if (updated == 1) {
      eventPublisher.publish(new BookingExpiredEvent(bookingId, expiredAt));
      log.info("좌석 hold 만료로 예매 {}를 EXPIRED로 전이했습니다. bookingNumber: {}", bookingId, bookingNumber);
    }
  }
}

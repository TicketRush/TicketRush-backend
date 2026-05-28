package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BookingCancelUseCase {

  private final BookingRepository bookingRepository;

  public void execute(Long bookingId) {
    Booking booking = bookingRepository.findById(bookingId).orElse(null);

    if (booking == null) {
      log.warn("보상 트랜잭션 스킵. 존재하지 않는 bookingId={}", bookingId);
      return;
    }

    booking.cancelPendingPayment();

    log.debug("보상 트랜잭션 정상 처리 : 예매가 취소(CANCELED) 되었습니다. bookingId: {}", bookingId);
  }
}

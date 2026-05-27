package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BookingExpireUseCase {

  private static final int PAYMENT_WAIT_MINUTES = 5;
  private static final int EXPIRE_BATCH_SIZE = 100;
  private static final Pageable EXPIRE_BATCH_REQUEST = PageRequest.of(0, EXPIRE_BATCH_SIZE);

  private final BookingRepository bookingRepository;
  private final Clock clock;

  public int execute() {
    LocalDateTime cutoff = LocalDateTime.now(clock).minusMinutes(PAYMENT_WAIT_MINUTES);
    int totalExpiredCount = 0;

    while (true) {
      List<Long> expiredPendingBookingIds =
          bookingRepository.findExpiredPendingBookingIds(
              BookingStatus.PENDING, cutoff, EXPIRE_BATCH_REQUEST);

      if (expiredPendingBookingIds.isEmpty()) {
        break;
      }

      totalExpiredCount +=
          bookingRepository.expirePendingBookingsByIds(
              expiredPendingBookingIds, BookingStatus.PENDING, BookingStatus.EXPIRED);

      if (expiredPendingBookingIds.size() < EXPIRE_BATCH_SIZE) {
        break;
      }
    }

    if (totalExpiredCount > 0) {
      log.info("만료된 PENDING 예매 {}건을 EXPIRED로 전이했습니다. cutoff: {}", totalExpiredCount, cutoff);
    }

    return totalExpiredCount;
  }
}

package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingExpireUseCase {

  private static final int PAYMENT_WAIT_MINUTES = 5;
  private static final int EXPIRE_BATCH_SIZE = 100;
  private static final int MAX_BATCH_ITERATIONS = 200;
  private static final Pageable EXPIRE_BATCH_REQUEST = PageRequest.of(0, EXPIRE_BATCH_SIZE);

  private final BookingRepository bookingRepository;
  private final TransactionTemplate transactionTemplate;
  private final EventPublisher eventPublisher;
  private final Clock clock;

  public int execute() {
    LocalDateTime expiredAt = LocalDateTime.now(clock);
    LocalDateTime cutoff = expiredAt.minusMinutes(PAYMENT_WAIT_MINUTES);
    int totalExpiredCount = 0;

    for (int iteration = 0; iteration < MAX_BATCH_ITERATIONS; iteration++) {
      List<Long> expiredPendingBookingIds =
          bookingRepository.findExpiredPendingBookingIds(
              BookingStatus.PENDING, cutoff, EXPIRE_BATCH_REQUEST);

      if (expiredPendingBookingIds.isEmpty()) {
        break;
      }

      totalExpiredCount += expireBatch(expiredPendingBookingIds, expiredAt);

      if (expiredPendingBookingIds.size() < EXPIRE_BATCH_SIZE) {
        break;
      }

      if (iteration == MAX_BATCH_ITERATIONS - 1) {
        log.warn(
            "만료 예매 배치 처리 최대 반복 횟수에 도달했습니다. " + "다음 스케줄에서 이어서 처리합니다. maxIterations: {}, cutoff: {}",
            MAX_BATCH_ITERATIONS,
            cutoff);
      }
    }

    if (totalExpiredCount > 0) {
      log.info("만료된 PENDING 예매 {}건을 EXPIRED로 전이했습니다. cutoff: {}", totalExpiredCount, cutoff);
    }

    return totalExpiredCount;
  }

  private int expireBatch(List<Long> bookingIds, LocalDateTime expiredAt) {
    return transactionTemplate.execute(
        status -> {
          int expiredCount = 0;

          for (Long bookingId : bookingIds) {
            int updated =
                bookingRepository.expirePendingBookingById(
                    bookingId, BookingStatus.PENDING, BookingStatus.EXPIRED);
            if (updated == 1) {
              expiredCount++;
              eventPublisher.publish(new BookingExpiredEvent(bookingId, expiredAt));
            }
          }

          return expiredCount;
        });
  }
}

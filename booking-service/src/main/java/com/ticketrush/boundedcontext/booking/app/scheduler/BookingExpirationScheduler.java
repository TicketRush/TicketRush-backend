package com.ticketrush.boundedcontext.booking.app.scheduler;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingExpireUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpirationScheduler {

  private final BookingExpireUseCase bookingExpireUseCase;

  @Scheduled(fixedDelay = 60000)
  @SchedulerLock(
      name = "scheduleExpirePendingBookingsFallbackLock",
      lockAtLeastFor = "50s",
      lockAtMostFor = "2m")
  public void scheduleExpirePendingBookingsFallback() {
    log.info("Fallback 스케줄러 동작: 만료된 PENDING 예매 검사 시작");
    bookingExpireUseCase.execute();
  }
}

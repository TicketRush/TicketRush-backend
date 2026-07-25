package com.ticketrush.boundedcontext.seat.app.scheduler;

import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.types.SeatStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SeatHeldGaugeMetrics {

  private final MeterRegistry meterRegistry;
  private final SeatRepository seatRepository;

  private final AtomicLong heldSeats = new AtomicLong(0);
  private final AtomicLong expiredHoldBacklog = new AtomicLong(0);

  @PostConstruct
  public void init() {
    Gauge.builder(MetricNames.SEAT_HELD, heldSeats, AtomicLong::get).register(meterRegistry);
    Gauge.builder(MetricNames.SEAT_HOLD_EXPIRED_BACKLOG, expiredHoldBacklog, AtomicLong::get)
        .register(meterRegistry);
  }

  /**
   * 두 게이지를 <b>같은 기준 시각</b>으로 갱신한다. 시각을 따로 뜨면 두 카운트가 서로 다른 경계로 갈려 합이 전체 HOLD 수와 어긋난다(대량 만료가 진행되는
   * 구간에서 특히 눈에 띈다).
   */
  @Scheduled(fixedDelay = 30000)
  public void refreshHeldSeats() {
    LocalDateTime now = LocalDateTime.now();
    heldSeats.set(seatRepository.countHeldSeats(SeatStatus.HOLD, now));
    expiredHoldBacklog.set(seatRepository.countExpiredHoldSeats(SeatStatus.HOLD, now));
  }
}

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

  @PostConstruct
  public void init() {
    Gauge.builder(MetricNames.SEAT_HELD, heldSeats, AtomicLong::get).register(meterRegistry);
  }

  @Scheduled(fixedDelay = 30000)
  public void refreshHeldSeats() {
    heldSeats.set(seatRepository.countHeldSeats(SeatStatus.HOLD, LocalDateTime.now()));
  }
}

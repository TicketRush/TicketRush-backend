package com.ticketrush.boundedcontext.booking.out.repository;

import com.ticketrush.global.types.SeatStatus;
import java.util.Optional;

public interface BookingSeatStatusReader {

  Optional<SeatStatus> findSeatStatus(Long seatId, Long performanceId);
}

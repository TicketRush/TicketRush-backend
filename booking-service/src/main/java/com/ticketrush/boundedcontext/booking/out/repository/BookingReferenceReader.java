package com.ticketrush.boundedcontext.booking.out.repository;

public interface BookingReferenceReader {

  boolean existsUserById(Long userId);

  boolean existsPerformanceById(Long performanceId);

  boolean existsSeatByIdAndPerformanceId(Long seatId, Long performanceId);
}

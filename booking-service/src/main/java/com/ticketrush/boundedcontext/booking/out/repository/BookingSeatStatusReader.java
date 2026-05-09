package com.ticketrush.boundedcontext.booking.out.repository;

public interface BookingSeatStatusReader {

  String findSeatStatus(Long seatId, Long performanceId);
}

package com.ticketrush.boundedcontext.booking.out.repository;

import com.ticketrush.global.types.PerformanceStatus;
import java.util.Optional;

public interface BookingReferenceReader {

  boolean existsUserById(Long userId);

  /**
   * 공연의 판매 상태. 값이 없으면 그 공연이 없거나 삭제됐다는 뜻이다 (#671).
   *
   * <p><b>존재 여부와 상태를 한 번에 답한다.</b> 예매 생성은 둘 다 필요한데, 따로 물으면 같은 행을 두 번 읽는다.
   *
   * <p>{@link BookingSeatStatusReader#findSeatStatus}와 같은 꼴이다 — 판정에 쓰는 상태는 문자열이 아니라 타입으로 올린다. 그래서
   * {@code PerformanceStatus}가 {@code SeatStatus}와 같은 자리(common {@code global.types})에 있다.
   */
  Optional<PerformanceStatus> findPerformanceStatus(Long performanceId);

  boolean existsSeatByIdAndPerformanceId(Long seatId, Long performanceId);
}

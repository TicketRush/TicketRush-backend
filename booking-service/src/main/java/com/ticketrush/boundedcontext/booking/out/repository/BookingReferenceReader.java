package com.ticketrush.boundedcontext.booking.out.repository;

import java.util.Optional;

public interface BookingReferenceReader {

  boolean existsUserById(Long userId);

  /**
   * 공연의 판매 상태. 값이 없으면 그 공연이 없다는 뜻이다 (#671).
   *
   * <p><b>존재 여부와 상태를 한 번에 답한다.</b> 예매 생성은 둘 다 필요한데, 따로 물으면 같은 행을 두 번 읽는다.
   *
   * <p><b>enum이 아니라 {@code String}이다.</b> {@code SeatStatus}는 common {@code global.types}에 있어
   * {@link BookingSeatStatusReader}가 타입으로 받을 수 있지만, {@code PerformanceStatus}는 performance-service의
   * {@code domain.types} 소유라 여기서 import할 수 없다. common으로 옮기는 것은 이 이슈의 범위 밖이다.
   */
  Optional<String> findPerformanceStatus(Long performanceId);

  boolean existsSeatByIdAndPerformanceId(Long seatId, Long performanceId);
}

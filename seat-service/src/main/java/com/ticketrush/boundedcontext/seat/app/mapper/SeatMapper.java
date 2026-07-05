package com.ticketrush.boundedcontext.seat.app.mapper;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusChangedResponse;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SeatMapper {

  // 좌석 상태 변경 이벤트 페이로드로 변환한다. DB의 id를 seatId로 매핑하고,
  // performanceId/seatLayoutId/seatNumber/seatStatus/holdExpiredAt은 동명 자동 매핑된다.
  @Mapping(source = "id", target = "seatId")
  SeatStatusChangedResponse toChangedResponse(Seat seat);
}

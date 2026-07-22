package com.ticketrush.boundedcontext.seat.app.dto.response;

import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;

/** 공연 좌석 배치도(seat map)를 구성하는 좌석 1건. {@code SeatLayout} 엔티티의 프로젝션이 아니다. */
public record SeatMapItemResponse(
    Long seatId,
    Long seatLayoutId,
    String seatNumber,
    SeatStatus seatStatus,
    LocalDateTime holdExpiredAt) {}

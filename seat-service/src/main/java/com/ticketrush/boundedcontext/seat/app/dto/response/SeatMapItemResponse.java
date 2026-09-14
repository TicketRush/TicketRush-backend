package com.ticketrush.boundedcontext.seat.app.dto.response;

import com.ticketrush.global.json.UtcLocalDateTimeSerializer;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 공연 좌석 배치도(seat map)를 구성하는 좌석 1건. {@code SeatLayout} 엔티티의 프로젝션이 아니다.
 *
 * <p>{@code seatRow}·{@code seatCol}은 1부터 시작하는 좌표다(#645). 렌더링은 이 좌표로 하고 {@code seatNumber}는 표시용으로만
 * 쓴다.
 */
public record SeatMapItemResponse(
    Long seatId,
    Long seatLayoutId,
    String seatNumber,
    Integer seatRow,
    Integer seatCol,
    SeatStatus seatStatus,
    @JsonSerialize(using = UtcLocalDateTimeSerializer.class) LocalDateTime holdExpiredAt) {}

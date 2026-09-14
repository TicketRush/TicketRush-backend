package com.ticketrush.boundedcontext.seat.app.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 좌석 배치 전체 크기(#645). 공연당 한 번만 싣는다 — 좌석마다 반복하지 않는다.
 *
 * <p><b>{@code totalRows × maxCols}는 좌석 수가 아니다.</b> 마지막 행이 부분 행일 수 있다({@code
 * SeatCreateDefaultLayoutUseCase} 참고). 좌석 수는 {@code seats} 길이로 센다.
 */
@Schema(description = "좌석 배치 크기")
public record SeatLayoutSizeResponse(
    @Schema(description = "총 행 수", example = "10") Integer totalRows,
    @Schema(description = "최대 열 수", example = "12") Integer maxCols) {}

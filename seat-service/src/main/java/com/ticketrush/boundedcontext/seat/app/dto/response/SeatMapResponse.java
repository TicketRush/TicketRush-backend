package com.ticketrush.boundedcontext.seat.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 공연 좌석맵 응답(#645). 배치 크기는 {@code layout}에 한 번, 좌석별 좌표는 {@code seats}에 싣는다.
 *
 * <p><b>배치도가 아직 없으면 {@code {"layout": null, "seats": []}}다.</b> 전역 Jackson 설정이 NON_NULL이라 그대로 두면
 * {@code layout} 키 자체가 사라지므로 ALWAYS를 명시한다 — "키 없음"과 "미생성"을 프론트가 구분하지 않아도 되게 한다.
 */
public record SeatMapResponse(
    @JsonInclude(JsonInclude.Include.ALWAYS) SeatLayoutSizeResponse layout,
    List<SeatMapItemResponse> seats) {}

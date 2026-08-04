package com.ticketrush.boundedcontext.seat.app.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 관리자 좌석 현황 모니터링 응답 (#562). 상단 요약 4종과 좌석 맵을 한 응답에 함께 싣는다 — 화면이 둘을 항상 같이 그리는데 따로 내리면 두 번 왕복하면서 새로고침
 * 사이에 요약과 맵이 서로 다른 시점을 보일 수 있다.
 *
 * <p>두 필드 모두 공개 API가 이미 주는 값과 같은 모양이다({@code seat-counts} · {@code seat-layouts}). 관리자용으로 새 집계나 새
 * 좌석 DTO를 만들지 않았고, 다른 것은 인증 경계와 <b>캐시를 경유하지 않는다는 점</b>뿐이다.
 */
@Schema(description = "관리자 좌석 현황 모니터링 응답 DTO")
public record SeatAdminMonitoringResponse(
    @Schema(description = "좌석 상태별 수 요약(전체·예약가능·판매완료·예약진행)") SeatStatusCountsResponse summary,
    @Schema(description = "공연의 전체 좌석 맵") List<SeatMapItemResponse> seats) {}

package com.ticketrush.boundedcontext.seat.app.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 관리자 좌석 현황 모니터링 응답 (#562). 상단 요약 4종과 좌석 맵을 한 응답에 함께 싣는다 — 화면이 둘을 항상 같이 그리므로 왕복을 한 번으로 줄인다.
 *
 * <p>두 필드 모두 공개 API가 이미 주는 값과 같은 모양이다({@code seat-counts} · {@code seat-layouts}). 관리자용으로 새 집계나 새
 * 좌석 DTO를 만들지 않았고, 다른 것은 인증 경계와 <b>캐시를 경유하지 않는다는 점</b>뿐이다.
 *
 * <p><b>두 필드가 같은 스냅샷이라는 보장은 없다.</b> 집계와 좌석 맵은 각자의 읽기 트랜잭션에서 조회되므로 그 사이 좌석이 전이하면 어긋날 수 있다. 게다가 둘은 집계
 * 규칙 자체가 다르다 — 요약은 만료 시각이 지난 선점을 예약 가능으로 세는 반면 맵은 원시 상태를 그대로 내리므로, 해제 대기 중인 좌석은 맵에 예약 진행 중으로 그려지면서
 * 요약의 예약 진행 수에는 잡히지 않는다. 관리자 화면이 수동 갱신이고 이 어긋남이 다음 갱신에서 수렴하므로 한 트랜잭션으로 묶지 않았다.
 */
@Schema(description = "관리자 좌석 현황 모니터링 응답 DTO")
public record SeatAdminMonitoringResponse(
    @Schema(description = "좌석 상태별 수 요약(전체·예약가능·판매완료·예약진행)") SeatStatusCountsResponse summary,
    @Schema(description = "공연의 전체 좌석 맵") List<SeatMapItemResponse> seats) {}

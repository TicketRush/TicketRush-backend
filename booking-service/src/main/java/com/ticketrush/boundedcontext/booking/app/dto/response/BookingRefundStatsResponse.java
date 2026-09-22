package com.ticketrush.boundedcontext.booking.app.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 환불 요약 통계 (#675). JPQL 생성자 표현식이 직접 만든다 — 파생 계산이 없어 중간 집계 record를 두지 않는다({@code
 * BookingPerformanceStatsRow}와 같은 방식).
 */
@Schema(description = "관리자 환불 요약 통계 응답 DTO")
public record BookingRefundStatsResponse(
    @Schema(
            description =
                "전체 환불 건수. 환불 진행 중(REFUNDING) + 환불 완료(REFUNDED) + "
                    + "미해결 실패(CONFIRMED이면서 환불 실패 이력 보유)다. "
                    + "**목록의 refund_status 필터와 무관하게 항상 전체 모집단을 센다.** "
                    + "결제 전 취소(CANCELED)와 미결제 만료(EXPIRED)는 환불이 아니므로 포함하지 않는다.",
            example = "312")
        long totalRefunds,
    @Schema(description = "환불 완료 건수(REFUNDED). PG 환불 성공 이벤트가 예매에 반영된 건만 센다.", example = "280")
        long completedRefunds) {}

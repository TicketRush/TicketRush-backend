package com.ticketrush.boundedcontext.booking.app.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 환불 요약 통계 (#675). JPQL 생성자 표현식이 직접 만든다 — 파생 계산이 없어 중간 집계 record를 두지 않는다({@code
 * BookingPerformanceStatsRow}와 같은 방식).
 *
 * <p>네 지표는 <b>서로 배타적 분할이고 전체가 나머지 셋의 합</b>이다. 예매 요약 통계({@code BookingAdminStatsResponse})의 네 지표가
 * 배타적이지 않은 것과 다르며, 같은 모집단을 예매 상태로 나눈 것이라 합이 어긋나면 그것이 곧 버그다.
 */
@Schema(description = "관리자 환불 요약 통계 응답 DTO")
public record BookingRefundStatsResponse(
    @Schema(
            description =
                "전체 환불 건수. 환불 진행 중(REFUNDING) + 환불 완료(REFUNDED) + "
                    + "미해결 실패(CONFIRMED이면서 환불 실패 이력 보유)이며 나머지 세 지표의 합이다. "
                    + "**목록의 refund_status 필터와 무관하게 항상 전체 모집단을 센다.** "
                    + "결제 전 취소(CANCELED)와 미결제 만료(EXPIRED)는 환불이 아니므로 포함하지 않는다.",
            example = "312")
        long totalRefunds,
    @Schema(
            description =
                "환불 진행 중 건수(REFUNDING). 정상 진행 건과 30분 이상 고착된 건을 모두 포함한다 — " + "고착 건만 세는 값이 아니다.",
            example = "12")
        long inProgressRefunds,
    @Schema(description = "환불 완료 건수(REFUNDED). PG 환불 성공 이벤트가 예매에 반영된 건만 센다.", example = "280")
        long completedRefunds,
    @Schema(
            description =
                "미해결 환불 실패 건수(CONFIRMED이면서 환불 실패 이력 보유). 재시도로 REFUNDING·REFUNDED에 간 건은 "
                    + "실패 이력이 남아 있어도 여기 세지 않고 각자의 현재 상태로 센다.",
            example = "20")
        long failedRefunds) {}

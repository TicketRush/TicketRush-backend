package com.ticketrush.boundedcontext.performance.app.dto.response;

import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 관리자 공연 목록 한 행 (#563). 공개 목록과 달리 판매·점유율·매출 같은 관리 집계 필드를 함께 내린다.
 *
 * <p>공연 자체 정보는 항상 채워지고, 집계 필드는 각 서비스 조회에 실패하면 비운다.
 *
 * <p><b>비운 필드는 응답에서 키째 사라진다.</b> 공통 Jackson 설정이 {@code NON_NULL}을 전역 기본값으로 걸어 두어 null 필드는 직렬화되지
 * 않는다. 클라이언트가 보는 것은 {@code "revenue": null}이 아니라 <b>키의 부재</b>다.
 */
@Schema(description = "관리자 공연 목록 행 DTO")
public record PerformanceAdminSummaryResponse(
    @Schema(description = "공연 ID", example = "12") Long performanceId,
    @Schema(description = "공연명", example = "뮤지컬 팬텀") String title,
    @Schema(description = "장르 코드", example = "MUSICAL") Genre genre,
    @Schema(description = "장르 한글명", example = "뮤지컬") String genreName,
    @Schema(description = "공연 날짜", example = "2026-09-01") LocalDate showDate,
    @Schema(description = "공연 시각", example = "19:30:00") LocalTime showTime,
    @Schema(description = "공연 상태", example = "ON_SALE") PerformanceStatus performanceStatus,
    @Schema(
            description =
                "판매된 좌석 수. **좌석 서비스의 실제 좌석 상태**이며 공연 등록 시 입력한 총 좌석 수와는 무관하다. "
                    + "좌석 서비스 조회에 실패했거나 좌석이 아직 생성되지 않은 공연이면 생략된다.",
            example = "38",
            nullable = true)
        Long soldSeats,
    @Schema(description = "전체 좌석 수. 좌석 서비스가 실제로 생성한 좌석 수다.", example = "120", nullable = true)
        Long totalSeats,
    @Schema(
            description = "좌석 점유율(0~1, 소수점 넷째 자리 반올림). 전체 좌석이 0이면 생략된다.",
            example = "0.3167",
            nullable = true)
        Double occupancyRate,
    @Schema(description = "매진 여부. 판매 좌석 수가 전체 좌석 수에 도달했는지다.", example = "false", nullable = true)
        Boolean soldOut,
    @Schema(
            description =
                "공연 매출. 확정된 예매의 실제 결제 금액 합이며 전체 기간이다. " + "예매 서비스 조회에 실패하면 생략되고, 예매가 한 건도 없으면 0이다.",
            example = "5320000",
            nullable = true)
        Long revenue) {}

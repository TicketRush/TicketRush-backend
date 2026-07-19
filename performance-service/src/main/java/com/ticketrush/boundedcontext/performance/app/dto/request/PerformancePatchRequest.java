package com.ticketrush.boundedcontext.performance.app.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ticketrush.boundedcontext.performance.app.support.NullOrNotBlank;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Schema(description = "공연 정보 수정 요청 (null 필드는 수정하지 않음)")
public record PerformancePatchRequest(
    @Schema(description = "공연명", example = "BTS World Tour 2025")
        @NullOrNotBlank
        @Size(max = 200, message = "공연명은 200자를 초과할 수 없습니다.")
        String title,
    @Schema(description = "출연진", example = "BTS")
        @NullOrNotBlank
        @Size(max = 200, message = "출연진은 200자를 초과할 수 없습니다.")
        String performer,
    @Schema(
            description = "장르 (MUSICAL/CONCERT/CLASSIC/JAZZ/FESTIVAL/BALLET/FANMEETING)",
            example = "CONCERT")
        Genre genre,
    @Schema(description = "공연 설명", example = "공연 설명입니다.", nullable = true) String description,
    @Schema(description = "공연 날짜 (yyyy-MM-dd)", example = "2025-08-15")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate showDate,
    @Schema(description = "공연 시작 시간 (HH:mm:ss)", example = "19:00:00")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
        LocalTime showTime,
    @Schema(description = "공연 시간 (분)", example = "120") @Positive(message = "공연 시간은 0보다 커야 합니다.")
        Integer durationMinutes,
    @Schema(description = "티켓 가격 (원)", example = "150000") @Positive(message = "가격은 0보다 커야 합니다.")
        Long price,
    @Schema(description = "총 좌석 수", example = "500") @Positive(message = "총 좌석 수는 1개 이상이어야 합니다.")
        Integer totalSeats,
    @Schema(description = "공연장 주소", example = "서울특별시 송파구 올림픽로 25 잠실종합운동장")
        @NullOrNotBlank
        @Size(max = 255, message = "주소는 255자를 초과할 수 없습니다.")
        String address,
    @Schema(
            description =
                "예매 오픈 시각 (yyyy-MM-dd HH:mm:ss, null=수정 안 함 — 해제하려면 "
                    + "DELETE /api/v1/performance/admin/{id}/booking-open-at 사용)",
            example = "2027-08-01 20:00:00")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime bookingOpenAt) {}

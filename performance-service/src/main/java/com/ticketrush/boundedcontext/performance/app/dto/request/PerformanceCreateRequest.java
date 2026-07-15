package com.ticketrush.boundedcontext.performance.app.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.Builder;

@Schema(description = "공연 등록 요청 (multipart JSON 파트)")
@Builder
public record PerformanceCreateRequest(
    @Schema(description = "공연명", example = "BTS World Tour 2025")
        @NotBlank(message = "공연명은 필수 입력 항목입니다.")
        String title,
    @Schema(description = "출연진", example = "BTS") @NotBlank(message = "출연진 정보는 필수입니다.")
        String performer,
    @Schema(
            description = "장르 (MUSICAL/CONCERT/CLASSIC/JAZZ/FESTIVAL/BALLET/FANMEETING)",
            example = "CONCERT")
        @NotNull(message = "장르를 선택해주세요.")
        Genre genre,
    @Schema(description = "공연 설명 (선택)", example = "BTS의 월드 투어 공연입니다.", nullable = true)
        String description,
    @Schema(description = "공연 날짜 (yyyy-MM-dd)", example = "2025-08-15")
        @NotNull(message = "공연 날짜는 필수입니다.")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate showDate,
    @Schema(description = "공연 시작 시간 (HH:mm:ss)", example = "19:00:00")
        @NotNull(message = "공연 시작 시간은 필수입니다.")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
        LocalTime showTime,
    @Schema(description = "공연 시간 (분)", example = "120")
        @NotNull(message = "공연 시간은 필수입니다.")
        @Positive(message = "공연 시간은 0보다 커야 합니다.")
        Integer durationMinutes,
    @Schema(description = "티켓 가격 (원)", example = "150000")
        @NotNull(message = "가격은 필수입니다.")
        @Positive(message = "가격은 0보다 커야 합니다.")
        Long price,
    @Schema(description = "총 좌석 수", example = "500")
        @NotNull(message = "총 좌석 수는 필수입니다.")
        @Positive(message = "총 좌석 수는 1개 이상이어야 합니다.")
        Integer totalSeats,
    @Schema(description = "공연장 주소", example = "서울특별시 송파구 올림픽로 25 잠실종합운동장")
        @NotBlank(message = "공연장 주소는 필수입니다.")
        String address,
    @Schema(
            description = "예매 오픈 시각 (yyyy-MM-dd HH:mm:ss, 선택 — 미설정 시 자동 오픈 없이 수동 전환만 가능)",
            example = "2027-08-01 20:00:00",
            nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime bookingOpenAt,
    @Schema(description = "편의시설 목록 (선택)", example = "[\"주차장\", \"수유실\", \"장애인석\"]", nullable = true)
        List<String> facilities) {}

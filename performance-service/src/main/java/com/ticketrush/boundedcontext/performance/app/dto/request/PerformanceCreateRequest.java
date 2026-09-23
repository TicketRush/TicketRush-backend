package com.ticketrush.boundedcontext.performance.app.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ticketrush.boundedcontext.performance.app.support.CharacterConstraints;
import com.ticketrush.boundedcontext.performance.app.support.JsonObjectMaxBytes;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.shared.performance.event.PerformanceCreatedEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

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
    @Schema(
            description =
                "총 좌석 수 (기본 배치는 120석 = 10행 x 12열, 최대 10000). " + "등록 시점에만 정할 수 있고 수정은 지원하지 않습니다.",
            example = "500")
        @NotNull(message = "총 좌석 수는 필수입니다.")
        @Positive(message = "총 좌석 수는 1개 이상이어야 합니다.")
        @Max(
            value = PerformanceCreatedEvent.MAX_TOTAL_SEATS,
            message = "총 좌석 수는 {value}개를 초과할 수 없습니다.")
        Integer totalSeats,
    @Schema(description = "공연장 주소", example = "서울특별시 송파구 올림픽로 25 잠실종합운동장")
        @NotBlank(message = "공연장 주소는 필수입니다.")
        String address,
    @Schema(
            description =
                "예매 오픈 시각 (yyyy-MM-dd HH:mm:ss, Asia/Seoul 기준, 선택 — " + "미설정 시 자동 오픈 없이 수동 전환만 가능)",
            example = "2027-08-01 20:00:00",
            nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime bookingOpenAt,
    @Schema(description = "편의시설 목록 (선택)", example = "[\"주차장\", \"수유실\", \"장애인석\"]", nullable = true)
        List<String> facilities,
    @Schema(
            description =
                "3D 캐릭터 구성 JSON 객체 (선택). 문자열이 아니라 JSON 객체 그대로 보낸다. "
                    + "백엔드는 내용을 해석하지 않으며(스키마는 프론트 소유),"
                    + " JSON 객체인지와 compact 직렬화 UTF-8 4,096바이트·중첩 32단 상한만 검증한다."
                    + " 저장 시 키 순서는 보존되지 않는다.",
            implementation = Object.class,
            example =
                "{\"schemaVersion\": 1, \"outfitModelId\": \"festival\", "
                    + "\"hairColor\": \"#151515\"}",
            nullable = true)
        @JsonObjectMaxBytes(
            max = CharacterConstraints.CONFIG_MAX_BYTES,
            maxDepth = CharacterConstraints.CONFIG_MAX_DEPTH,
            message = "캐릭터 구성은 JSON 객체여야 하며 {max}바이트·중첩 {maxDepth}단을 넘을 수 없습니다.")
        JsonNode characterConfig,
    @Schema(
            description = "캐릭터 한마디 (선택, 최대 50자). " + "빈 문자열(공백만 있는 문자열 포함)은 '없음'으로 저장한다.",
            example = "공연장에서 만나요!",
            nullable = true)
        @Size(
            max = CharacterConstraints.MESSAGE_MAX_LENGTH,
            message = "캐릭터 한마디는 {max}자를 초과할 수 없습니다.")
        String characterMessage,
    @Schema(
            description = "메인 배너 등록 여부. 생략하거나 false이면 배너를 등록하지 않고, " + "true이면 공연 등록과 함께 배너를 등록한다.",
            example = "true",
            defaultValue = "false",
            nullable = true)
        Boolean displayOnBanner,
    @Schema(
            description =
                "배너 전용 소제목 (선택, 최대 200자). "
                    + "displayOnBanner가 true일 때만 사용하며, "
                    + "빈 문자열 또는 공백 문자열은 소제목 없음으로 처리한다.",
            example = "여름밤의 재즈 향연",
            nullable = true)
        @Size(max = 200, message = "배너 소제목은 200자를 초과할 수 없습니다.")
        String bannerSubtitle) {}

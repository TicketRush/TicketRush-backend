package com.ticketrush.boundedcontext.performance.app.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ticketrush.boundedcontext.performance.app.support.CharacterConstraints;
import com.ticketrush.boundedcontext.performance.app.support.JsonObjectMaxBytes;
import com.ticketrush.boundedcontext.performance.app.support.NullOrNotBlank;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import tools.jackson.databind.JsonNode;

/**
 * 공연 정보 수정 요청. null 필드는 수정하지 않는다.
 *
 * <p><b>{@code characterMessage}만 예외로 빈 문자열(공백만 있는 문자열 포함)을 삭제 신호로 읽는다(#650).</b> 프론트가 한마디를 지우는 동작이
 * 필요한데 이 계약에서 null은 "수정 안 함"이라 삭제를 표현할 값이 없었다. 그래서 이 필드에는 {@code @NullOrNotBlank}를 붙이지 않는다 — 붙이면 삭제
 * 요청이 400이 된다. 캐릭터 구성({@code characterConfig})은 프론트가 제거 기능을 두지 않기로 해 삭제 규칙이 없다(덮어쓰기만).
 *
 * <p><b>총 좌석 수는 이 요청에 없다(#590).</b> 좌석 수를 실제로 바꾸려면 이미 예매·선점된 좌석을 지우고 번호를 다시 매겨야 하는데 그건 좌석 도메인의 별개
 * 작업이다. 여기에 필드만 두면 DB의 총 좌석 수만 바뀌고 실제 좌석은 그대로여서 두 값이 조용히 갈린다. 총 좌석 수는 등록 시점에만 정한다.
 */
@Schema(
    description =
        "공연 정보 수정 요청 (null 필드는 수정하지 않음. 총 좌석 수는 등록 시점에만 정할 수 있어 이 요청에 없음. "
            + "characterMessage는 빈 문자열(공백만 있는 문자열 포함)이면 삭제)")
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
        LocalDateTime bookingOpenAt,
    @Schema(
            description =
                "3D 캐릭터 구성 JSON 객체 (null=수정 안 함, 객체를 보내면 통째로 덮어씀. 삭제 규칙 없음). "
                    + "백엔드는 내용을 해석하지 않으며"
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
            description = "캐릭터 한마디 (최대 50자). null=수정 안 함, 빈 문자열(공백만 있는 문자열 포함)=삭제",
            example = "공연장에서 만나요!",
            nullable = true)
        @Size(
            max = CharacterConstraints.MESSAGE_MAX_LENGTH,
            message = "캐릭터 한마디는 {max}자를 초과할 수 없습니다.")
        String characterMessage) {}

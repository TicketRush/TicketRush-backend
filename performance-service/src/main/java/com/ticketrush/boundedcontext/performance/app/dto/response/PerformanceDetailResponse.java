package com.ticketrush.boundedcontext.performance.app.dto.response;

import com.ticketrush.boundedcontext.performance.app.support.SeoulWallClockSerializer;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 공연 상세 응답. 관리자 수정 화면도 별도 관리자 상세 API 없이 이 응답을 재사용한다(#650 프론트 결정).
 *
 * <p>{@code characterConfig}는 저장된 JSON을 트리 그대로 싣는다 — 전역 SNAKE_CASE는 POJO 프로퍼티명에만 적용되고 트리의 키는 건드리지
 * 않는다(테스트로 고정). 캐릭터가 없는 공연은 두 필드가 null이라 전역 NON_NULL로 키째 빠진다. 프론트는 두 키를 optional로 처리한다.
 */
public record PerformanceDetailResponse(
    Long performanceId,
    String title,
    String performer,
    Genre genre,
    String description,
    LocalDate showDate,
    LocalTime showTime,
    Integer durationMinutes,
    Long price,
    Integer totalSeats,
    String address,
    PerformanceStatus performanceStatus,
    // 맨 LocalDateTime으로 두면 전역 JacksonConfig 포맷을 타고 존 없이 나가, 클라이언트가 KST 로 읽을지 UTC 로
    // 읽을지 정할 근거가 응답 안에 없다 (#671). 표기를 Z 가 아니라 +09:00 으로 고른 이유는 직렬화기 Javadoc 참고 —
    // 이 필드는 어드민 수정 화면이 상세 응답을 그대로 폼에 되돌려 저장하는 왕복을 탄다(#650).
    @Schema(
            description =
                "예매 오픈 시각 (yyyy-MM-dd'T'HH:mm:ss+09:00, Asia/Seoul). 요청과 같은 벽시계 값에 오프셋만 붙는다."
                    + " 오픈 시각이 없으면 키가 빠짐",
            example = "2027-08-01T20:00:00+09:00",
            nullable = true)
        @JsonSerialize(using = SeoulWallClockSerializer.class)
        LocalDateTime bookingOpenAt,
    String imageMainUrl,
    String image3dUrl,
    List<String> imageGalleryUrls,
    List<String> facilities,
    // JsonNode를 그대로 두면 springdoc이 isObject()·isNull() 같은 getter를 프로퍼티로 문서화한다(실측). Object로 문서화해야 "임의
    // 객체"가 된다.
    @Schema(
            implementation = Object.class,
            description = "3D 캐릭터 구성 JSON 객체 (저장한 값 그대로, 키 순서는 보존되지 않음). 캐릭터가 없으면 키가 빠짐",
            nullable = true)
        JsonNode characterConfig,
    @Schema(description = "캐릭터 한마디 (최대 50자). 없으면 키가 빠짐", nullable = true)
        String characterMessage) {}

package com.ticketrush.boundedcontext.performance.app.dto.response;

import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

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

package com.ticketrush.boundedcontext.banner.app.dto.response;

import java.time.LocalDate;

/**
 * 메인 화면 상단 캐러셀 배너 응답.
 *
 * <p>배너 전용 정보와 연결된 공연 정보를 조합하여 반환한다.
 *
 * <ul>
 *   <li>{@code id}: 배너 ID
 *   <li>{@code performanceId}: 클릭 시 이동할 공연 ID
 *   <li>{@code title}: 공연 제목
 *   <li>{@code subtitle}: 배너 전용 소제목
 *   <li>{@code description}: 공연 소개
 *   <li>{@code date}: 공연 날짜
 *   <li>{@code imageUrl}: 공연 대표 이미지 URL
 *   <li>{@code order}: 배너 노출 순서
 * </ul>
 *
 * <p>{@code title}, {@code description}, {@code date}, {@code imageUrl}은 연결된 공연 데이터에서 가져온다.
 *
 * <p>{@code subtitle}, {@code order}는 Banner 데이터에서 가져온다.
 */
public record BannerResponse(
    Long id,
    Long performanceId,
    String title,
    String subtitle,
    String description,
    LocalDate date,
    String imageUrl,
    Integer order) {}

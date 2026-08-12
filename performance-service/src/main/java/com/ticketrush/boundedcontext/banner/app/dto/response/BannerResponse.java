package com.ticketrush.boundedcontext.banner.app.dto.response;

import java.time.LocalDate;

/**
 * 메인 배너 응답 (#564).
 *
 * <p><b>필드명이 엔티티와 갈리는 곳이 셋 있고 전부 의도된 것이다.</b> 이 record는 프론트 {@code BannerItem} 계약을 그대로 옮긴 것이라, 백엔드
 * 도메인 용어와 어긋나는 이름을 여기 한 곳에 가둔다.
 *
 * <ul>
 *   <li>{@code order} — 엔티티는 {@code displayOrder}다. 컬럼명으로 {@code order}를 쓸 수 없어서 갈렸을 뿐 계약은 {@code
 *       order}다. 이 이름을 {@code displayOrder}로 바꾸면 응답이 {@code display_order}로 나가 프론트가 값을 읽지 못한다.
 *   <li>{@code linkConcertId} — 엔티티는 {@code linkPerformanceId}다. 백엔드 도메인 용어는 performance인데 프론트 계약은
 *       concert라, 낯선 용어가 도메인 코드로 번지지 않도록 경계에서만 바꾼다.
 *   <li>{@code date} — 엔티티는 {@code displayDate}다. 공연 날짜지 노출 기간이 아니다.
 * </ul>
 *
 * <p>{@code LocalDate}는 {@code JacksonConfig}가 {@code yyyy-MM-dd}로 직렬화하므로 프론트가 문자열로 그대로 출력하는 형식과
 * 맞는다. null 필드는 {@code NON_NULL} 설정에 따라 키째 빠지며, 프론트 타입도 전부 옵셔널이라 그대로 호환된다.
 *
 * <p><b>계약의 출처.</b> 이 필드 구성은 추정이 아니라 프론트 레포의 실제 타입에서 옮긴 것이다. 대조 대상이 이 레포 밖에 있어 리뷰어가 확인할 수 있도록 출처를
 * 남긴다(TicketRush-frontend, 브랜치 develop).
 *
 * <ul>
 *   <li>{@code src/types/domain/banner.ts} — {@code BannerItem} 인터페이스, 커밋 {@code
 *       7693a72}(2026-07-09)
 *   <li>{@code src/api/mocks/banners.ts} — 시드 문구의 원본, 커밋 {@code 7de8ef7}(2026-07-09)
 *   <li>{@code src/components/concert/BannerSlider.tsx} — 실제 렌더러, 커밋 {@code c1b7409}(2026-06-01)
 * </ul>
 *
 * <p><b>이슈 #564 본문의 제안 스펙과는 다르다.</b> 본문은 {@code bannerId}·{@code imageUrl}·{@code linkUrl}·{@code
 * sortOrder}·{@code isActive}를 제안했으나 실제 프론트 타입에는 {@code imageUrl}이 없고(배너는 텍스트+이모지다) 나머지 이름도 위와 같이
 * 다르다. 본문이 아니라 실물을 따랐다.
 *
 * <p>이름이 하나라도 어긋나면 axios-case-converter를 거친 뒤 {@code undefined}가 되어 <b>에러 없이 조용히</b> 그 값만 화면에서
 * 사라진다. {@code BannerControllerTest}가 JSON 경로를 직접 단언해 이 계약을 고정한다.
 */
public record BannerResponse(
    Long id,
    String title,
    String subtitle,
    String description,
    String tagLabel,
    String iconEmoji,
    LocalDate date,
    Long linkConcertId,
    Integer order) {}

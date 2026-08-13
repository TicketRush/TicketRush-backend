package com.ticketrush.boundedcontext.banner.domain.entity;

import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 메인 화면 상단 캐러셀 배너 (#564).
 *
 * <p><b>왜 performance-service 안에 있으면서 boundedcontext가 따로인가.</b> 배너는 공연과 데이터를 공유하지 않는다 — 마케팅
 * 문구(제목·부제·태그 ·이모지)는 {@code Performance}에 없는 값이고, 공연 연결({@link #linkPerformanceId})조차 선택이라 공연에 붙지
 * 않는 배너가 성립한다. 그래서 공연 목록을 재사용하는 대신 전용 리소스로 만들었다. {@code docs/ddd-directory-structure.md}가 {@code
 * boundedcontext/{도메인}} 단위를 규정하므로 도메인이 다르면 디렉토리도 갈리는 것이 문서를 따르는 쪽이다. 기존 7개 서비스가 컨텍스트를 하나씩만 가진 것은
 * 규칙이 아니라 서비스명과 도메인명이 같았기 때문이다. 배포 단위를 performance-service로 둔 것은 배너가 메인 화면에서 공연 목록과 함께 조회되고, 그것만으로
 * 서비스를 하나 더 띄울 이유가 없어서다. 이 결정과 그에 따른 경로·게이트웨이 라우트 배치는 <b>ADR 0013</b>에 남겼다 — 레포에서 한 서비스가 컨텍스트를 둘 갖는
 * 첫 사례다.
 *
 * <p><b>이미지가 없다.</b> 배너는 이미지가 아니라 텍스트 + 이모지 + 그라데이션 배경으로 그려진다(프론트 {@code BannerSlider}). 배경색은 프론트가
 * 인덱스로 순환시키는 하드코딩이라 서버가 내려주지 않는다. 그래서 이미지 업로드(#153) 경로를 재사용할 일이 없다.
 *
 * <p><b>{@link #linkPerformanceId}는 공연 존재를 검증하지 않는다.</b> FK도 조인도 걸지 않았다. {@code Performance}가 소프트
 * 삭제(@SQLRestriction)라 FK를 걸어도 행이 남아 죽은 링크를 막지 못하고, 레포 관례상 {@code booking.performance_id}에도 FK가 없다.
 * null이면 클릭 비활성 배너이며 프론트가 이미 그렇게 처리한다.
 *
 * <p><b>인덱스도 캐시도 없다.</b> 배너는 3~5행이라 풀스캔이 인덱스보다 싸고, 프론트가 조회 결과를 5분간 캐싱한다({@code useBanners}의 {@code
 * staleTime}). 배너가 수십 건으로 늘면 {@code (deactivated_at, display_order)} 인덱스와 캐싱을 함께 검토한다.
 *
 * <p><b>노출 제어는 {@link #deactivatedAt} 하나로 한다.</b> null이면 노출, 값이 있으면 숨김이며 그 시각이 곧 언제 내렸는지의 기록이다. 소프트
 * 삭제 컬럼을 따로 두지 않았다 — 조회 전용 리소스에서 "지운다"와 "안 보이게 한다"는 결과가 같고, 컬럼이 둘이면 운영자가 어느 쪽을 써야 하는지 헷갈린다. boolean
 * 대신 datetime을 쓴 것은 이 레포 스키마에 boolean 컬럼이 하나도 없어, 타입을 새로 들이면 스냅샷·prod DDL을 손으로 맞춰야 하기 때문이다.
 *
 * <p><b>필터를 {@code @SQLRestriction}으로 박지 않았다.</b> 엔티티 레벨로 강제하면 비활성 배너를 조회할 방법이 아예 사라져, 나중에 관리자 API를
 * 붙일 때 그 자체가 벽이 된다. 대신 {@code BannerRepository}의 조회 메서드가 조건을 갖는다.
 *
 * <p><b>운영 — 배너 데이터는 수동으로 넣는다.</b> 관리자 CRUD가 없다(배너 관리 화면이 디자인에 없어 호출자가 없다). local은 {@code
 * BannerDataInitRunner}가 시드하고, prod는 아래 런북으로 직접 넣는다. 문구·순서·노출 변경도 prod {@code UPDATE}다.
 *
 * <p><b>알려진 한계 — {@code display_date}는 시간이 지나면 과거가 된다.</b> 시드 3건의 날짜는 2026-09-15 / 2026-10-20 /
 * 2026-11-10이며, 그 시점이 지나면 "지난 날짜를 광고하는 배너"가 된다. 갱신 수단은 prod {@code UPDATE}뿐이다. 근본 해결은 관리자 CRUD를
 * 만들거나 배너를 실제 공연에 연결하는 것인데 둘 다 #564 범위 밖으로 뒀다.
 *
 * <p><b>prod 수동 DDL 런북.</b> prod는 {@code ddl-auto: validate}라 이 테이블이 없으면 performance-service가 부팅을
 * 거부한다 — 배너뿐 아니라 공연 목록·상세·관리자 대시보드가 전부 내려간다. 그래서 <b>애플리케이션 배포보다 먼저</b> 실행해야 한다. 반대로 테이블이 먼저 있어도 구버전
 * jar에는 무해하다(validate는 엔티티에 매핑된 테이블만 검사한다). 아래 DDL은 로컬 MySQL에 {@code ddl-auto=update}로 띄워
 * Hibernate가 실제 생성한 것을 {@code SHOW CREATE TABLE}로 뽑은 것이다.
 *
 * <pre>
 * CREATE TABLE `banner` (
 *   `banner_id` bigint NOT NULL AUTO_INCREMENT,
 *   `created_at` datetime(6) DEFAULT NULL,
 *   `updated_at` datetime(6) DEFAULT NULL,
 *   `deactivated_at` datetime(6) DEFAULT NULL,
 *   `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
 *   `display_date` date DEFAULT NULL,
 *   `display_order` int NOT NULL,
 *   `icon_emoji` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
 *   `link_performance_id` bigint DEFAULT NULL,
 *   `subtitle` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
 *   `tag_label` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
 *   `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
 *   PRIMARY KEY (`banner_id`)
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
 *
 * -- INSERT 전에 반드시 확인한다. 이 INSERT에는 멱등 가드가 없어서, 배포 중단·재시도로 두 번
 * -- 실행되면 같은 배너가 6건이 되고 display_order가 1,1,2,2,3,3으로 중복돼 캐러셀에 같은 배너가
 * -- 두 번 뜬다. 되돌리는 수단도 SQL뿐이다(관리자 API 없음). 0이 아니면 실행하지 않는다.
 * SELECT COUNT(*) FROM banner;
 *
 * INSERT INTO `banner`
 *   (`created_at`, `updated_at`, `title`, `subtitle`, `description`,
 *    `tag_label`, `icon_emoji`, `display_date`, `display_order`)
 * VALUES
 *   (NOW(6), NOW(6), 'Summer Jazz Night', '여름밤의 재즈 향연',
 *    '세계적인 재즈 뮤지션과 함께하는 특별한 밤', '조기 예매 할인', '🎵', '2026-09-15', 1),
 *   (NOW(6), NOW(6), 'K-Pop Mega Concert', '최고의 아이돌 스타 집결',
 *    '팬들이 기다린 드림 라인업 공개!', 'VIP 패키지 판매중', '🎤', '2026-10-20', 2),
 *   (NOW(6), NOW(6), 'Classical Gala', '클래식의 정수를 만나다',
 *    '세계 3대 오케스트라 내한 공연', '프리미엄 좌석 한정', '🎹', '2026-11-10', 3);
 * </pre>
 *
 * <p>실행 후 {@code SHOW TABLES LIKE 'banner'}와 {@code SELECT COUNT(*) FROM banner}로 확인한다. 이모지가 깨지면 접속
 * 세션의 문자셋을 확인한다({@code SET NAMES utf8mb4}) — 테이블은 utf8mb4다.
 */
@Entity
@Table(name = "banner")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "banner_id"))
public class Banner extends AutoIdBaseEntity {

  @Column(nullable = false, length = 200)
  private String title;

  @Column(length = 200)
  private String subtitle;

  @Column(length = 500)
  private String description;

  /** 좌상단 뱃지 문구("조기 예매 할인" 등). */
  @Column(length = 50)
  private String tagLabel;

  /** 제목 앞과 우측 워터마크에 쓰이는 이모지. 조합 이모지(ZWJ)는 코드포인트가 여러 개라 길이를 넉넉히 잡았다. */
  @Column(length = 20)
  private String iconEmoji;

  /** 배너가 알리는 공연 날짜. 노출 기간이 아니다 — 노출 여부는 {@link #deactivatedAt}가 정한다. */
  private LocalDate displayDate;

  /** 클릭 시 이동할 공연 ID. null이면 클릭 비활성이며 존재 여부를 검증하지 않는다(클래스 javadoc 참고). */
  private Long linkPerformanceId;

  /**
   * 캐러셀 노출 순서(오름차순). 컬럼명이 {@code order}가 아닌 이유는 MySQL·H2 양쪽에서 예약어이기 때문이다. 응답 JSON은 프론트 계약에 맞춰
   * {@code order}로 나간다({@code BannerResponse}).
   */
  @Column(nullable = false)
  private Integer displayOrder;

  /** null이면 노출. 값이 있으면 숨김이며 그 시각이 내린 시점이다. */
  private LocalDateTime deactivatedAt;

  @Builder
  public Banner(
      String title,
      String subtitle,
      String description,
      String tagLabel,
      String iconEmoji,
      LocalDate displayDate,
      Long linkPerformanceId,
      Integer displayOrder) {
    this.title = title;
    this.subtitle = subtitle;
    this.description = description;
    this.tagLabel = tagLabel;
    this.iconEmoji = iconEmoji;
    this.displayDate = displayDate;
    this.linkPerformanceId = linkPerformanceId;
    this.displayOrder = displayOrder;
  }
}

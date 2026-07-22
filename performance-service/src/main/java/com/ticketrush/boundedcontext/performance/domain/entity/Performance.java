package com.ticketrush.boundedcontext.performance.domain.entity;

import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import com.ticketrush.global.status.ErrorStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.SQLRestriction;

/**
 * 공연.
 *
 * <p><b>동적 UPDATE (#459).</b> 이 어노테이션이 붙기 전에는 엔티티를 로드해 더티 체킹으로 쓰는 경로(PATCH·상태 변경·소프트 삭제)가 변경하지 않은
 * 컬럼까지 포함한 전체 행 UPDATE를 내보냈고, 그래서 로드 이후 다른 트랜잭션이 커밋한 값이 stale 값으로 조용히 덮어써졌다. 특히 예매 오픈 시각 해제(#438)가
 * 커밋된 뒤 PATCH가 커밋되면 해제된 bookingOpenAt이 부활해 공연이 어드민 모르게 자동 전환 대상으로 복귀했고, bookingOpenAt이 다시 채워지므로
 * 스케줄러의 self-heal조차 불가능했다.
 *
 * <p>{@code @Version}(낙관적 락)이 아니라 {@code @DynamicUpdate}를 택한 이유는 <b>이 경합의 상대편이 둘 다 벌크 JPQL이기
 * 때문이다.</b> PerformanceRepository의 clearBookingOpenAt과 bulkTransitionStatusByBookingOpenAtDue는
 * version을 증가시키지도 검사하지도 않으므로(seat·booking도 같은 한계 — ADR 0005), {@code @Version}만 달아서는 PATCH의 버전 검사가
 * 그대로 통과한다. JPQL {@code UPDATE VERSIONED}로 벌크까지 version을 올리면 낙관적 락도 성립하지만(ADR 0005가 booking에 대해 같은
 * 선택지를 남겨 뒀다), 스케줄러가 10초마다 도는 탓에 어드민 PATCH가 산발적으로 409를 맞게 된다. 여기서 원한 것은 충돌을 알리는 게 아니라 서로 무관한 수정이
 * 조용히 둘 다 성립하는 것이라 동적 UPDATE를 골랐다.
 *
 * <p>동적 UPDATE로 충분한 이유는 경합하는 경로들이 <b>서로소 컬럼을 쓰기 때문이다.</b> PATCH는 update()가 받은 non-null 필드만, 상태 변경은
 * performance_status만, 소프트 삭제는 deleted_at만, 두 벌크는 각각 performance_status와 booking_open_at만 건드린다. 변경
 * 컬럼만 SET에 실리면 서로의 쓰기를 덮을 일이 없다. 다섯 경로가 공유하는 컬럼은 updated_at 하나뿐인데, Auditing 리스너와 벌크 JPQL이 언제나 현재
 * 시각을 넣으므로 stale 값이 실릴 수 없어 무해하다.
 *
 * <p>이 불변식이 깨지는 경우는 셋이다.
 *
 * <ol>
 *   <li>두 경로가 같은 컬럼을 다투게 되는 경우.
 *   <li>stale read에 의존하는 가드나 계산이 추가되는 경우. 변경 컬럼만 써도 lost update가 난다.
 *   <li>{@code @ElementCollection}(imageGalleryUrls·facilities)을 <b>로드된</b> 엔티티에서 바꾸는 경우.
 *       updateUrls()가 컬렉션 인스턴스를 통째로 교체하므로 Hibernate는 컬렉션 테이블을 전량 DELETE 후 재INSERT하는데, 동적 UPDATE는
 *       basic 컬럼의 SET 절만 좁힐 뿐 컬렉션 DML에는 관여하지 않는다. 지금은 생성 직후에만 호출돼 문제가 없지만 이미지 수정 기능이 붙으면 달라진다.
 * </ol>
 *
 * <p><b>(1)에 해당하는 경로는 이미 있다.</b> 어드민 둘이 같은 필드를 PATCH하거나 동시에 상태를 바꾸면 같은 컬럼을 다투므로 동적 UPDATE로 갈리지 않고
 * 나중 커밋이 이긴다. 그중 changeStatus()는 canTransitionTo()가 로드된(=stale일 수 있는) 상태를 읽어 전이를 검증하는 (2) 유형이기도 해서,
 * 어드민 둘이 각각 CANCELED와 CLOSED를 커밋하면 종단 상태가 덮인다. 소프트 삭제와 겹칠 때도 PATCH 내용이 이미 보이지 않는 행에 적용되고 사라진다. 이런
 * 경합을 조용히 넘기지 않고 사용자에게 드러내야 한다면, 그때 낙관적 락({@code @Version} + 벌크의 {@code UPDATE VERSIONED})을 검토한다.
 *
 * <p>남는 한계로, PATCH가 bookingOpenAt을 명시로 실어 보내면 해제와 같은 컬럼을 다투므로 커밋 순서대로 last-write-wins다. 이건 어드민의 의도적
 * 쓰기라 stale 부활과 구분되며 이번 범위에서 제외했다.
 *
 * <p>{@code @DynamicInsert}는 이번 범위 밖이다. null 컬럼을 INSERT문에서 빼 DB default가 먹게 하는 기능이라 이 이슈가 다루는 동시
 * 쓰기와는 무관하다.
 */
@Entity
@Table(name = "performance")
@DynamicUpdate
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "performance_id"))
public class Performance extends AutoIdBaseEntity {

  @Column(nullable = false, length = 200)
  private String title;

  @Column(length = 200)
  private String performer;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Genre genre;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(nullable = false)
  private LocalDate showDate;

  @Column(nullable = false)
  private LocalTime showTime;

  @Column(nullable = false)
  private Integer durationMinutes;

  @Column(nullable = false)
  private Long price;

  @Column(nullable = false)
  private Integer totalSeats;

  @Column(length = 255)
  private String address;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PerformanceStatus performanceStatus;

  // null이면 자동 전환 대상이 아니며 어드민 수동 전환만 가능하다
  @Column(name = "booking_open_at")
  private LocalDateTime bookingOpenAt;

  private String image3dUrl;

  private String imageMainUrl;

  @ElementCollection
  @CollectionTable(name = "performance_images", joinColumns = @JoinColumn(name = "performance_id"))
  @Column(name = "image_url")
  @OrderColumn(name = "image_url_order")
  private List<String> imageGalleryUrls = new ArrayList<>();

  @ElementCollection
  @CollectionTable(
      name = "performance_facilities",
      joinColumns = @JoinColumn(name = "performance_id"))
  @Column(name = "facility_name")
  @OrderColumn(name = "facility_order")
  private List<String> facilities = new ArrayList<>();

  @Column(name = "deleted_at")
  private LocalDateTime deletedAt;

  @Builder
  public Performance(
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
      LocalDateTime bookingOpenAt,
      String image3dUrl,
      String imageMainUrl,
      List<String> imageGalleryUrls,
      List<String> facilities) {
    this.title = title;
    this.performer = performer;
    this.genre = genre;
    this.description = description;
    this.showDate = showDate;
    this.showTime = showTime;
    this.durationMinutes = durationMinutes;
    this.price = price;
    this.totalSeats = totalSeats;
    this.address = address;
    this.bookingOpenAt = bookingOpenAt;
    this.image3dUrl = image3dUrl;
    this.imageMainUrl = imageMainUrl;
    this.imageGalleryUrls = imageGalleryUrls != null ? imageGalleryUrls : new ArrayList<>();
    this.facilities = facilities != null ? facilities : new ArrayList<>();

    // [비즈니스 로직] 생성 시점에는 항상 UPCOMING 상태로 고정 (안전성 확보)
    this.performanceStatus = PerformanceStatus.UPCOMING;
  }

  public void updateUrls(String mainImageUrl, String model3dUrl, List<String> galleryUrls) {
    this.imageMainUrl = mainImageUrl;
    this.image3dUrl = model3dUrl;
    this.imageGalleryUrls = galleryUrls != null ? galleryUrls : new ArrayList<>();
  }

  public void update(
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
      LocalDateTime bookingOpenAt) {
    if (title != null) {
      this.title = title;
    }
    if (performer != null) {
      this.performer = performer;
    }
    if (genre != null) {
      this.genre = genre;
    }
    if (description != null) {
      this.description = description;
    }
    if (showDate != null) {
      this.showDate = showDate;
    }
    if (showTime != null) {
      this.showTime = showTime;
    }
    if (durationMinutes != null) {
      this.durationMinutes = durationMinutes;
    }
    if (price != null) {
      this.price = price;
    }
    if (totalSeats != null) {
      this.totalSeats = totalSeats;
    }
    if (address != null) {
      this.address = address;
    }
    if (bookingOpenAt != null) {
      this.bookingOpenAt = bookingOpenAt;
    }
  }

  public void softDelete() {
    this.deletedAt = LocalDateTime.now();
  }

  public boolean canTransitionTo(PerformanceStatus target) {
    return this.performanceStatus.canTransitionTo(target);
  }

  public void changeStatus(PerformanceStatus newStatus) {
    if (!canTransitionTo(newStatus)) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_INVALID_STATUS_TRANSITION);
    }
    this.performanceStatus = newStatus;
  }
}

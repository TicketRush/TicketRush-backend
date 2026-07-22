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

/*
 * 동적 UPDATE (#459). Performance를 로드해 더티 체킹으로 쓰는 경로(PATCH·상태 변경·소프트 삭제)는 변경하지 않은
 * 컬럼까지 포함한 전체 행 UPDATE를 내보낸다. 그러면 로드 이후 다른 트랜잭션이 커밋한 값이 stale 값으로 조용히
 * 덮어써진다. 특히 예매 오픈 시각 해제(#438)가 커밋된 뒤 PATCH가 커밋되면 해제된 bookingOpenAt이 부활해 공연이
 * 어드민 모르게 자동 전환 대상으로 복귀하고, bookingOpenAt이 다시 채워지므로 스케줄러의 self-heal조차 불가능하다.
 *
 * @Version(낙관적 락)이 아니라 @DynamicUpdate를 쓰는 이유는 stale write의 상대편이 둘 다 벌크 JPQL이기 때문이다.
 * PerformanceRepository의 clearBookingOpenAt과 bulkTransitionStatusByBookingOpenAtDue는 version을
 * 증가시키지도 검사하지도 않으므로(seat·booking도 같은 한계 — ADR 0005), @Version을 달아도 PATCH의 버전 검사는
 * 그대로 통과하고 전체 컬럼 UPDATE가 나간다. 즉 낙관적 락으로는 이 경합이 잡히지 않는다.
 *
 * 동적 UPDATE만으로 안전한 이유는 경합하는 경로들이 <b>서로소 컬럼을 쓰기 때문이다.</b> PATCH는 update()가 받은
 * non-null 필드만, 상태 변경은 performance_status만, 소프트 삭제는 deleted_at만, 두 벌크는 각각
 * performance_status와 booking_open_at만 건드린다. 변경 컬럼만 SET에 실리면 서로의 쓰기를 덮을 일이 없다.
 *
 * 이 불변식이 깨지는 경우는 둘이다. (1) 두 경로가 같은 컬럼을 다투게 되거나, (2) read-modify-write 계산(예: 잔여
 * 좌석 수 증감)이 엔티티에 추가되면 변경 컬럼만 써도 lost update가 난다. 지금 update()·changeStatus()·
 * softDelete()는 전부 대입형이라 (2)에 해당하지 않는다. 그때는 @Version 도입을 검토한다.
 *
 * 남는 한계로, PATCH가 bookingOpenAt을 명시로 실어 보내면 해제와 같은 컬럼을 다투므로 커밋 순서대로
 * last-write-wins다. 이건 어드민의 의도적 쓰기라 stale 부활과 구분되며 이번 범위에서 제외했다.
 *
 * @DynamicInsert는 도입하지 않는다. INSERT는 생성 경로 하나뿐이라 경합이 없다.
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

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
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "performance")
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
      String address) {
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

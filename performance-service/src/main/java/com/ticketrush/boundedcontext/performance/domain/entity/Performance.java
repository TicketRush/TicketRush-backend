package com.ticketrush.boundedcontext.performance.domain.entity;

import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "performance")
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
  private List<String> imageGalleryUrls;

  @ElementCollection
  @CollectionTable(
      name = "performance_facilities",
      joinColumns = @JoinColumn(name = "performance_id"))
  @Column(name = "facility_name")
  private List<String> facilities;

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
    this.imageGalleryUrls = imageGalleryUrls;
    this.facilities = facilities;

    // [비즈니스 로직] 생성 시점에는 항상 UPCOMING 상태로 고정 (안전성 확보)
    this.performanceStatus = PerformanceStatus.UPCOMING;
  }

  public void updateUrls(String mainImageUrl, String model3dUrl, List<String> galleryUrls) {
    this.imageMainUrl = mainImageUrl;
    this.image3dUrl = model3dUrl;
    this.imageGalleryUrls = galleryUrls;
  }

  public boolean canTransitionTo(PerformanceStatus target) {
    return this.performanceStatus.canTransitionTo(target);
  }

  public void changeStatus(PerformanceStatus newStatus) {
    this.performanceStatus = newStatus;
  }
}

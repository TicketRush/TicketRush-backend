package com.ticketrush.boundedcontext.performance.app.dto.response;

import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

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
    List<String> facilities) {}

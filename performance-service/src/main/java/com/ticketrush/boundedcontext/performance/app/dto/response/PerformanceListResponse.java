package com.ticketrush.boundedcontext.performance.app.dto.response;

import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import java.time.LocalDate;
import java.time.LocalTime;

public record PerformanceListResponse(
    Long performanceId,
    String title,
    String performer,
    Genre genre,
    LocalDate showDate,
    LocalTime showTime,
    String address,
    String imageMainUrl,
    PerformanceStatus performanceStatus,
    Long price) {}

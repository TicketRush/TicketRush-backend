package com.ticketrush.boundedcontext.seat.out.repository;

import com.ticketrush.boundedcontext.seat.domain.entity.SeatLayout;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatLayoutRepository extends JpaRepository<SeatLayout, Long> {

  boolean existsByPerformanceId(Long performanceId);
}

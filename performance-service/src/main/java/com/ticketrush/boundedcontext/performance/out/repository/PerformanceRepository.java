package com.ticketrush.boundedcontext.performance.out.repository;

import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerformanceRepository
    extends JpaRepository<Performance, Long>, PerformanceRepositoryCustom {

  @EntityGraph(attributePaths = {"imageGalleryUrls", "facilities"})
  Optional<Performance> findDetailById(Long id);
}

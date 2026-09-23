package com.ticketrush.boundedcontext.banner.out.repository;

import com.ticketrush.boundedcontext.banner.domain.entity.Banner;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BannerRepository extends JpaRepository<Banner, Long> {

  /**
   * 배너를 캐러셀 노출 순서대로 조회한다.
   *
   * @return displayOrder 및 id 오름차순으로 정렬된 배너 목록
   */
  List<Banner> findAllByOrderByDisplayOrderAscIdAsc();

  /**
   * 공연 ID로 연결된 배너를 조회한다.
   *
   * @param performanceId 공연 ID
   * @return 해당 공연의 배너
   */
  Optional<Banner> findByPerformanceId(Long performanceId);

  /**
   * 해당 공연의 배너 등록 여부를 확인한다.
   *
   * @param performanceId 공연 ID
   * @return 배너가 등록되어 있으면 true
   */
  boolean existsByPerformanceId(Long performanceId);

  /**
   * 삭제된 배너보다 뒤에 있는 배너를 순서대로 조회한다.
   *
   * @param displayOrder 삭제된 배너의 노출 순서
   * @return 노출 순서를 앞으로 당겨야 하는 배너 목록
   */
  List<Banner> findByDisplayOrderGreaterThanOrderByDisplayOrderAscIdAsc(Integer displayOrder);
}

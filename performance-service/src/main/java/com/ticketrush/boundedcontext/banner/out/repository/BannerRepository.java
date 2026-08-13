package com.ticketrush.boundedcontext.banner.out.repository;

import com.ticketrush.boundedcontext.banner.domain.entity.Banner;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BannerRepository extends JpaRepository<Banner, Long> {

  /**
   * 노출 중인 배너를 캐러셀 순서대로 조회한다.
   *
   * <p><b>정렬 책임이 전적으로 서버에 있다.</b> 프론트 {@code BannerSlider}는 받은 배열의 인덱스를 그대로 슬라이드 순서로 쓴다 — mock 시절에는
   * {@code mockGetBanners}가 클라이언트에서 정렬했지만 그 코드는 mock 함수 안에만 있어 실제 API로 전환되면 사라진다.
   *
   * <p>{@code displayOrder}만으로는 부족해 {@code id}까지 tie-break로 넣었다. 배너 순서는 사람이 수동 {@code UPDATE}로 넣는
   * 값이라 중복이 들어올 수 있고, 동순위의 순서를 정렬 키가 정하지 않으면 실행 계획에 따라 배너 순서가 흔들린다.
   *
   * <p>{@code deactivatedAt} 조건을 {@code @SQLRestriction} 대신 여기 둔 이유는 {@code Banner} 클래스 javadoc에
   * 있다.
   */
  List<Banner> findByDeactivatedAtIsNullOrderByDisplayOrderAscIdAsc();
}

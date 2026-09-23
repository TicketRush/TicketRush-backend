package com.ticketrush.boundedcontext.banner.in.api.v1;

import com.ticketrush.boundedcontext.banner.app.dto.response.BannerResponse;
import com.ticketrush.boundedcontext.banner.app.facade.BannerFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 메인 화면 상단 캐러셀에 노출할 배너 조회 API.
 *
 * <p>배너는 공연과 별도 도메인으로 관리하지만 배포 단위는 performance-service에 포함된다. 외부에서 접근할 수 있도록 게이트웨이에 {@code
 * /api/v1/banner/**} 경로가 performance-service 라우트로 등록되어 있어야 한다.
 *
 * <p>배너 테이블에는 공연 ID, 배너 전용 소제목, 노출 순서만 저장한다. 공연 제목, 소개, 날짜, 대표 이미지는 배너에 연결된 공연에서 조회하여 응답을 구성한다.
 *
 * <p>인증이 필요 없는 공개 API다. performance-service {@code SecurityConfig}에서 관리자 API 경로를 제외한 요청을 허용하므로 별도
 * 인증 설정 없이 접근할 수 있다.
 */
@Tag(name = "Banner", description = "메인 배너 API")
@RestController
@RequestMapping("/api/v1/banner")
@RequiredArgsConstructor
public class BannerController {

  private final BannerFacade bannerFacade;

  @Operation(
      summary = "메인 배너 목록 조회",
      description =
          """
      메인 화면 상단 캐러셀에 노출할 배너 목록을 조회합니다.
      인증 없이 누구나 접근할 수 있습니다.

      배너 응답은 배너 정보와 연결된 공연 정보를 조합하여 구성합니다.

      - `id`: 배너 ID
      - `performance_id`: 연결된 공연 ID
      - `title`: 공연 제목
      - `subtitle`: 배너 전용 소제목
      - `description`: 공연 소개
      - `date`: 공연 날짜
      - `image_url`: 공연 대표 이미지 URL
      - `order`: 배너 노출 순서

      배너용 이미지는 별도로 관리하지 않으며, 연결된 공연의 기존 대표 이미지를 사용합니다.
      배너를 클릭할 때는 `performance_id`를 사용하여 해당 공연 상세 페이지로 이동할 수 있습니다.

      결과는 `order` 오름차순으로 반환됩니다.
      연결된 공연이 존재하지 않거나 삭제된 경우 해당 배너는 결과에서 제외됩니다.
      등록된 배너가 없으면 `result`에 빈 배열을 반환합니다.

      기존 응답에 포함됐던 `tag_label`, `icon_emoji`, `link_concert_id`는 더 이상 반환하지 않습니다.
      """)
  @GetMapping
  public ResponseEntity<ApiResponse<List<BannerResponse>>> getBanners() {
    return ApiResponse.onSuccess(SuccessStatus.OK, bannerFacade.getBanners());
  }
}

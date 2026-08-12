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
 * 메인 배너 조회 API (#564).
 *
 * <p><b>경로가 {@code /api/v1/performance} 아래가 아니다.</b> 배너는 공연의 하위 리소스가 아니라 전용 리소스라 모듈명을 경로로 쓴다(컨벤션
 * §3, 단수형). 대신 배포 단위는 performance-service이므로 <b>게이트웨이 라우트에 {@code /api/v1/banner/**}를 함께 등록해야</b>
 * 외부에서 도달한다 — 게이트웨이는 서비스별 경로 와일드카드로만 라우팅해서, 라우트를 빠뜨리면 애플리케이션이 정상이어도 404가 된다(ticket-service의 {@code
 * /api/v1/entries/**}가 같은 이유로 뒤늦게 추가된 선례다).
 *
 * <p>인증이 필요 없다. performance-service {@code SecurityConfig}가 admin prefix 외에는 {@code permitAll}이라 별도
 * 설정 없이 공개된다.
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
          메인 화면 상단 캐러셀에 노출할 배너 목록을 조회합니다. 인증 없이 누구나 접근 가능합니다.

          **정렬은 서버가 고정합니다** — 노출 순서(order) 오름차순이며, 같은 순서가 여럿이면 배너 ID 오름차순입니다.
          클라이언트는 응답 배열의 순서를 그대로 슬라이드 순서로 사용하면 됩니다.

          노출이 중지된 배너는 응답에서 빠집니다. 배너가 하나도 없으면 빈 배열을 반환합니다.

          **배너는 이미지가 없습니다.** 텍스트와 이모지로 구성되며 배경 그라데이션은 클라이언트가 정합니다.
          `link_concert_id`가 없는 배너는 클릭 대상이 아닙니다.
          """)
  @GetMapping
  public ResponseEntity<ApiResponse<List<BannerResponse>>> getBanners() {
    return ApiResponse.onSuccess(SuccessStatus.OK, bannerFacade.getBanners());
  }
}

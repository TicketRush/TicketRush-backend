package com.ticketrush.boundedcontext.banner.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.banner.app.dto.response.BannerResponse;
import com.ticketrush.boundedcontext.banner.app.facade.BannerFacade;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.support.WebMvcSliceTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 배너 조회 API의 <b>응답 계약</b>을 고정한다 (#564).
 *
 * <p>프론트 {@code BannerItem}과 필드명이 어긋나면 값이 조용히 사라지므로(axios가 snake_case를 camelCase로 되돌리는 구조라 이름이 하나만
 * 틀려도 undefined가 된다) JSON 경로를 직접 단언한다.
 */
@WebMvcSliceTest(BannerController.class)
@Import({CustomSecurityProperties.class, SecurityConfig.class})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class BannerControllerTest {

  private static final String BASE_URL = "/api/v1/banner";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BannerFacade bannerFacade;

  @Test
  @DisplayName("인증 없이 조회해도 200이다 — 공개 API다")
  void getBanners_noAuth_success() throws Exception {
    given(bannerFacade.getBanners()).willReturn(List.of(fullBanner()));

    mockMvc.perform(get(BASE_URL)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("프론트 계약대로 snake_case 필드명으로 내려간다")
  void getBanners_fieldNamesMatchFrontContract() throws Exception {
    given(bannerFacade.getBanners()).willReturn(List.of(fullBanner()));

    mockMvc
        .perform(get(BASE_URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result[0].id").value(1))
        .andExpect(jsonPath("$.result[0].title").value("Summer Jazz Night"))
        .andExpect(jsonPath("$.result[0].subtitle").value("여름밤의 재즈 향연"))
        .andExpect(jsonPath("$.result[0].description").value("세계적인 재즈 뮤지션과 함께하는 특별한 밤"))
        .andExpect(jsonPath("$.result[0].tag_label").value("조기 예매 할인"))
        .andExpect(jsonPath("$.result[0].icon_emoji").value("🎵"))
        .andExpect(jsonPath("$.result[0].link_concert_id").value(42))
        // 날짜는 프론트가 포맷팅 없이 그대로 출력하므로 yyyy-MM-dd여야 한다
        .andExpect(jsonPath("$.result[0].date").value("2026-09-15"))
        // display_order로 나가면 프론트가 순서를 읽지 못한다
        .andExpect(jsonPath("$.result[0].order").value(1))
        .andExpect(jsonPath("$.result[0].display_order").doesNotExist());
  }

  @Test
  @DisplayName("옵셔널 필드가 null이면 키 자체가 빠진다")
  void getBanners_nullFieldsOmitted() throws Exception {
    given(bannerFacade.getBanners())
        .willReturn(List.of(new BannerResponse(1L, "제목만", null, null, null, null, null, null, 1)));

    mockMvc
        .perform(get(BASE_URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result[0].title").value("제목만"))
        .andExpect(jsonPath("$.result[0].subtitle").doesNotExist())
        .andExpect(jsonPath("$.result[0].link_concert_id").doesNotExist());
  }

  @Test
  @DisplayName("배너가 없으면 빈 배열이다 — null이 아니다")
  void getBanners_emptyArray() throws Exception {
    // 프론트는 배열 길이로 렌더 여부를 판단한다. result가 통째로 빠지면 배너 영역이 사라지는 게 아니라
    // 클라이언트에서 length 접근이 깨진다.
    given(bannerFacade.getBanners()).willReturn(List.of());

    mockMvc
        .perform(get(BASE_URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").isArray())
        .andExpect(jsonPath("$.result").isEmpty());
  }

  @Test
  @DisplayName("페이징 메타는 실리지 않는다")
  void getBanners_noPaginationInfo() throws Exception {
    given(bannerFacade.getBanners()).willReturn(List.of(fullBanner()));

    mockMvc
        .perform(get(BASE_URL))
        .andExpect(status().isOk())
        // 키 이름은 반드시 wire 이름(pagination_info)이어야 한다. camelCase로 단언하면 어떤 응답에도
        // 없는 키라 항상 통과해, 실수로 Page/Slice 오버로드를 쓰더라도 잡지 못한다.
        .andExpect(jsonPath("$.pagination_info").doesNotExist());
  }

  @Test
  @DisplayName("여러 건이면 받은 순서가 그대로 직렬화된다")
  void getBanners_preservesOrder() throws Exception {
    // 프론트 BannerSlider는 배열 인덱스를 그대로 슬라이드 순서로 쓴다. 정렬은 UseCase가 하지만,
    // 그 결과가 직렬화를 거치며 뒤집히지 않는다는 것은 이 경계에서만 확인된다.
    given(bannerFacade.getBanners())
        .willReturn(List.of(banner(1L, "첫째", 1), banner(2L, "둘째", 2), banner(3L, "셋째", 3)));

    mockMvc
        .perform(get(BASE_URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.length()").value(3))
        .andExpect(jsonPath("$.result[0].title").value("첫째"))
        .andExpect(jsonPath("$.result[1].title").value("둘째"))
        .andExpect(jsonPath("$.result[2].title").value("셋째"));
  }

  private BannerResponse banner(Long id, String title, Integer order) {
    return new BannerResponse(id, title, null, null, null, null, null, null, order);
  }

  private BannerResponse fullBanner() {
    return new BannerResponse(
        1L,
        "Summer Jazz Night",
        "여름밤의 재즈 향연",
        "세계적인 재즈 뮤지션과 함께하는 특별한 밤",
        "조기 예매 할인",
        "🎵",
        LocalDate.of(2026, 9, 15),
        42L,
        1);
  }
}

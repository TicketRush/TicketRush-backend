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
 * 배너 조회 API의 응답 계약을 검증한다.
 *
 * <p>배너 응답은 배너 엔티티의 정보와 연결된 공연 정보를 조합하여 반환한다. 전역 Jackson 설정에 따라 record의 camelCase 필드는 snake_case로
 * 직렬화된다.
 */
@WebMvcSliceTest(BannerController.class)
@Import({CustomSecurityProperties.class, SecurityConfig.class})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class BannerControllerTest {

  private static final String BASE_URL = "/api/v1/banner";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BannerFacade bannerFacade;

  @Test
  @DisplayName("인증 없이 배너 목록을 조회하면 200을 반환한다")
  void getBanners_noAuth_success() throws Exception {
    given(bannerFacade.getBanners()).willReturn(List.of(fullBanner()));

    mockMvc.perform(get(BASE_URL)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("변경된 배너 응답 계약에 맞는 필드를 snake_case로 반환한다")
  void getBanners_fieldNamesMatchFrontContract() throws Exception {
    given(bannerFacade.getBanners()).willReturn(List.of(fullBanner()));

    mockMvc
        .perform(get(BASE_URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result[0].id").value(1))
        .andExpect(jsonPath("$.result[0].performance_id").value(42))
        .andExpect(jsonPath("$.result[0].title").value("Summer Jazz Night"))
        .andExpect(jsonPath("$.result[0].subtitle").value("여름밤의 재즈 향연"))
        .andExpect(jsonPath("$.result[0].description").value("세계적인 재즈 뮤지션과 함께하는 특별한 밤"))
        .andExpect(jsonPath("$.result[0].date").value("2026-09-15"))
        .andExpect(jsonPath("$.result[0].image_url").value("https://example.com/main.jpg"))
        .andExpect(jsonPath("$.result[0].order").value(1))
        .andExpect(jsonPath("$.result[0].display_order").doesNotExist())
        .andExpect(jsonPath("$.result[0].link_concert_id").doesNotExist())
        .andExpect(jsonPath("$.result[0].tag_label").doesNotExist())
        .andExpect(jsonPath("$.result[0].icon_emoji").doesNotExist());
  }

  @Test
  @DisplayName("선택 필드가 null이면 해당 키가 응답에서 빠진다")
  void getBanners_nullFieldsOmitted() throws Exception {
    given(bannerFacade.getBanners())
        .willReturn(List.of(new BannerResponse(1L, 42L, "제목만", null, null, null, null, 1)));

    mockMvc
        .perform(get(BASE_URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result[0].id").value(1))
        .andExpect(jsonPath("$.result[0].performance_id").value(42))
        .andExpect(jsonPath("$.result[0].title").value("제목만"))
        .andExpect(jsonPath("$.result[0].order").value(1))
        .andExpect(jsonPath("$.result[0].subtitle").doesNotExist())
        .andExpect(jsonPath("$.result[0].description").doesNotExist())
        .andExpect(jsonPath("$.result[0].date").doesNotExist())
        .andExpect(jsonPath("$.result[0].image_url").doesNotExist());
  }

  @Test
  @DisplayName("배너가 없으면 result에 null이 아닌 빈 배열을 반환한다")
  void getBanners_emptyArray() throws Exception {
    given(bannerFacade.getBanners()).willReturn(List.of());

    mockMvc
        .perform(get(BASE_URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").isArray())
        .andExpect(jsonPath("$.result").isEmpty());
  }

  @Test
  @DisplayName("배너 목록 응답에는 페이징 정보가 포함되지 않는다")
  void getBanners_noPaginationInfo() throws Exception {
    given(bannerFacade.getBanners()).willReturn(List.of(fullBanner()));

    mockMvc
        .perform(get(BASE_URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination_info").doesNotExist());
  }

  @Test
  @DisplayName("여러 배너는 Facade에서 반환한 순서 그대로 직렬화된다")
  void getBanners_preservesOrder() throws Exception {
    given(bannerFacade.getBanners())
        .willReturn(
            List.of(
                banner(1L, 101L, "첫째", 1), banner(2L, 102L, "둘째", 2), banner(3L, 103L, "셋째", 3)));

    mockMvc
        .perform(get(BASE_URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.length()").value(3))
        .andExpect(jsonPath("$.result[0].id").value(1))
        .andExpect(jsonPath("$.result[0].performance_id").value(101))
        .andExpect(jsonPath("$.result[0].title").value("첫째"))
        .andExpect(jsonPath("$.result[0].order").value(1))
        .andExpect(jsonPath("$.result[1].id").value(2))
        .andExpect(jsonPath("$.result[1].performance_id").value(102))
        .andExpect(jsonPath("$.result[1].title").value("둘째"))
        .andExpect(jsonPath("$.result[1].order").value(2))
        .andExpect(jsonPath("$.result[2].id").value(3))
        .andExpect(jsonPath("$.result[2].performance_id").value(103))
        .andExpect(jsonPath("$.result[2].title").value("셋째"))
        .andExpect(jsonPath("$.result[2].order").value(3));
  }

  private BannerResponse banner(Long id, Long performanceId, String title, Integer order) {

    return new BannerResponse(id, performanceId, title, null, null, null, null, order);
  }

  private BannerResponse fullBanner() {
    return new BannerResponse(
        1L,
        42L,
        "Summer Jazz Night",
        "여름밤의 재즈 향연",
        "세계적인 재즈 뮤지션과 함께하는 특별한 밤",
        LocalDate.of(2026, 9, 15),
        "https://example.com/main.jpg",
        1);
  }
}

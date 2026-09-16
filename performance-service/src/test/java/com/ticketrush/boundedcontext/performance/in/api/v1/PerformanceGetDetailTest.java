package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceDetailResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(PerformanceController.class)
@Import({CustomSecurityProperties.class, JacksonConfig.class, SecurityConfig.class})
class PerformanceGetDetailTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PerformanceFacade performanceFacade;

  final String baseUrl = "/api/v1/performance";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PerformanceDetailResponse sampleResponse() {
    return sampleResponse(null, null);
  }

  private PerformanceDetailResponse sampleResponse(
      JsonNode characterConfig, String characterMessage) {
    return new PerformanceDetailResponse(
        1L,
        "레미제라블",
        "홍길동",
        Genre.MUSICAL,
        "공연 안내 내용",
        LocalDate.of(2025, 9, 1),
        LocalTime.of(19, 0),
        150,
        80000L,
        500,
        "서울특별시 중구 세종대로 110",
        PerformanceStatus.ON_SALE,
        LocalDateTime.of(2025, 8, 1, 20, 0),
        "https://s3.example.com/main.jpg",
        "https://s3.example.com/model.glb",
        List.of("https://s3.example.com/gallery1.jpg"),
        List.of("주차장", "수유실"),
        characterConfig,
        characterMessage);
  }

  @Test
  @DisplayName("공연 ID로 상세 조회 시 200과 상세 데이터를 반환한다")
  void getPerformanceDetail_success() throws Exception {
    when(performanceFacade.getPerformanceDetail(1L)).thenReturn(sampleResponse());

    mockMvc
        .perform(get(baseUrl + "/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.performance_id").value(1))
        .andExpect(jsonPath("$.result.title").value("레미제라블"))
        .andExpect(jsonPath("$.result.genre").value("MUSICAL"))
        .andExpect(jsonPath("$.result.facilities[0]").value("주차장"));
  }

  @Test
  @DisplayName("인증 없이도 공연 상세 조회 시 200을 반환한다")
  void getPerformanceDetail_unauthenticated_success() throws Exception {
    when(performanceFacade.getPerformanceDetail(1L)).thenReturn(sampleResponse());

    mockMvc
        .perform(get(baseUrl + "/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));
  }

  @Test
  @DisplayName("존재하지 않는 공연 ID 조회 시 404를 반환한다")
  void getPerformanceDetail_notFound() throws Exception {
    doThrow(new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND))
        .when(performanceFacade)
        .getPerformanceDetail(999L);

    mockMvc
        .perform(get(baseUrl + "/999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value("PERFORMANCE_404_001"));
  }

  /*
   * #650 — 캐릭터 구성은 저장된 트리를 그대로 싣는다. 전역 SNAKE_CASE는 record 컴포넌트명(character_config)에만 적용되고
   * JsonNode 안쪽 키(schemaVersion·hairColor)는 건드리지 않는다는 것을 고정한다. 이게 깨지면 프론트가 보낸 키가 응답에서 바뀐다.
   */
  @Test
  @DisplayName("캐릭터가 있는 공연은 characterConfig 트리의 키가 변환 없이 그대로 실리고 한마디도 실린다")
  void getPerformanceDetail_characterConfig_keysUntouched() throws Exception {
    JsonNode config =
        JSON.readTree(
            "{\"schemaVersion\":1,\"outfitModelId\":\"festival\","
                + "\"nested\":{\"hairColor\":\"#151515\"},\"list\":[1,2]}");
    when(performanceFacade.getPerformanceDetail(1L))
        .thenReturn(sampleResponse(config, "공연장에서 만나요!"));

    mockMvc
        .perform(get(baseUrl + "/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.character_config.schemaVersion").value(1))
        .andExpect(jsonPath("$.result.character_config.outfitModelId").value("festival"))
        .andExpect(jsonPath("$.result.character_config.nested.hairColor").value("#151515"))
        .andExpect(jsonPath("$.result.character_config.list[1]").value(2))
        .andExpect(jsonPath("$.result.character_config.schema_version").doesNotExist())
        .andExpect(jsonPath("$.result.character_message").value("공연장에서 만나요!"));
  }

  // 전역 NON_NULL은 POJO 프로퍼티에만 적용된다. 트리 안의 null 값(의상별 색상처럼 "이 의상에는 없음"을 뜻하는
  // nullable 필드)은 프론트 스키마의 일부라 지워지면 안 된다. Jackson 3 ObjectNode 직렬화는
  // JsonNodeFeature.WRITE_NULL_PROPERTIES(기본 true)만 보므로 지금은 유지되지만, spring.jackson.* 로 그
  // feature를 끄면 조용히 깨진다 — 그래서 고정한다.
  @Test
  @DisplayName("characterConfig 트리 안의 null 값은 NON_NULL에 걸리지 않고 그대로 실린다")
  void getPerformanceDetail_characterConfig_innerNullKept() throws Exception {
    JsonNode config = JSON.readTree("{\"accessory\":null,\"nested\":{\"festivalTopColor\":null}}");
    when(performanceFacade.getPerformanceDetail(1L)).thenReturn(sampleResponse(config, null));

    mockMvc
        .perform(get(baseUrl + "/1"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"accessory\":null")))
        .andExpect(content().string(containsString("\"festivalTopColor\":null")))
        .andExpect(jsonPath("$.result.character_message").doesNotExist());
  }

  @Test
  @DisplayName("캐릭터가 없는 공연은 character_config·character_message 키가 응답에서 빠진다")
  void getPerformanceDetail_noCharacter_keysAbsent() throws Exception {
    when(performanceFacade.getPerformanceDetail(1L)).thenReturn(sampleResponse());

    mockMvc
        .perform(get(baseUrl + "/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.title").value("레미제라블"))
        .andExpect(jsonPath("$.result.character_config").doesNotExist())
        .andExpect(jsonPath("$.result.character_message").doesNotExist());
  }
}

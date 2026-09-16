package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformancePatchRequest;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.boundedcontext.performance.app.support.CharacterConstraints;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.support.WebMvcSliceTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.multipart.MultipartFile;

/**
 * #650 — 캐릭터 필드의 요청 검증과 model3d 파트 선택화를 컨트롤러 경계에서 고정한다.
 *
 * <p>{@code @WebMvcSliceTest}는 전역 {@code JacksonConfig}(SNAKE_CASE·NON_NULL)를 포함하므로 요청 키는 프론트가 실제로
 * 보내는 {@code character_config}·{@code character_message}다. 검증 실패는 전부 {@code VALID_400_001}로 나간다.
 */
@WebMvcSliceTest(PerformanceAdminController.class)
@Import(CustomSecurityProperties.class)
@WithMockUser
class PerformanceCharacterConfigValidationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PerformanceFacade performanceFacade;

  private static final String BASE_URL = "/api/v1/performance/admin";

  private static final String VALID_BASE =
      """
      "title":"정상 제목","performer":"정상 가수","genre":"CONCERT","show_date":"2030-01-01",
      "show_time":"19:00:00","duration_minutes":120,"price":50000,"total_seats":100,"address":"서울시"
      """;

  /** compact 직렬화가 정확히 {@code bytes}바이트가 되는 단일 키 객체. {"k":"…"}의 고정 부분이 8바이트다. */
  private static String objectOfBytes(int bytes) {
    return "{\"k\":\"" + "x".repeat(bytes - 8) + "\"}";
  }

  private ResultActions create(String characterFieldsJson, MockMultipartFile... extraParts)
      throws Exception {
    String body =
        "{"
            + VALID_BASE.strip()
            + (characterFieldsJson.isEmpty() ? "" : "," + characterFieldsJson)
            + "}";
    MockMultipartFile jsonPart =
        new MockMultipartFile(
            "request", "", MediaType.APPLICATION_JSON_VALUE, body.getBytes(StandardCharsets.UTF_8));
    MockMultipartFile mainImage =
        new MockMultipartFile("mainImage", "i.png", "image/png", "t".getBytes());

    var builder = multipart(BASE_URL).file(jsonPart).file(mainImage);
    for (MockMultipartFile part : extraParts) {
      builder = builder.file(part);
    }
    return mockMvc.perform(builder.with(csrf()));
  }

  private ResultActions patchWith(String bodyJson) throws Exception {
    return mockMvc.perform(
        patch(BASE_URL + "/1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(bodyJson)
            .with(csrf()));
  }

  // ---------- 등록: model3d 선택화 ----------

  @Test
  @DisplayName("등록: model3d 파트 없이 보내도 201이고 파사드에 model3d=null로 전달된다")
  void create_withoutModel3dPart_created() throws Exception {
    create("").andExpect(status().isCreated());

    then(performanceFacade).should().createPerformance(any(), any(), isNull(), any());
  }

  @Test
  @DisplayName("등록: 0바이트 model3d 파트는 400이 아니라 빈 MultipartFile로 파사드까지 전달된다 (미전송 판정은 유스케이스)")
  void create_emptyModel3dPart_passedAsEmptyFile() throws Exception {
    MockMultipartFile emptyModel3d =
        new MockMultipartFile("model3d", "", "application/octet-stream", new byte[0]);

    create("", emptyModel3d).andExpect(status().isCreated());

    ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
    then(performanceFacade).should().createPerformance(any(), any(), captor.capture(), any());
    assertThat(captor.getValue()).isNotNull();
    assertThat(captor.getValue().isEmpty()).isTrue();
  }

  // ---------- 등록: characterConfig ----------

  @Test
  @DisplayName("등록: characterConfig가 JSON 객체가 아니면(문자열·배열·숫자) 400 VALID_400_001")
  void create_characterConfigNotObject_badRequest() throws Exception {
    for (String notObject : new String[] {"\"abc\"", "[1,2]", "42", "true"}) {
      create("\"character_config\":" + notObject)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALID_400_001"))
          .andExpect(jsonPath("$.message").value(containsString("characterConfig")));
    }
    then(performanceFacade).should(never()).createPerformance(any(), any(), any(), any());
  }

  @Test
  @DisplayName("등록: characterConfig가 정확히 4,096바이트면 통과하고 4,097바이트면 400")
  void create_characterConfigSizeBoundary() throws Exception {
    int max = CharacterConstraints.CONFIG_MAX_BYTES;

    create("\"character_config\":" + objectOfBytes(max)).andExpect(status().isCreated());

    create("\"character_config\":" + objectOfBytes(max + 1))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALID_400_001"))
        .andExpect(jsonPath("$.message").value(containsString("4096")));
  }

  /** 최상위 객체가 1단인 {@code depth}단 중첩 객체. */
  private static String nestedObjectOfDepth(int depth) {
    return "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1);
  }

  @Test
  @DisplayName("등록: characterConfig 중첩이 32단이면 통과하고 33단이면 400 (MySQL 깊이 100 제한을 요청에서 먼저 막는다)")
  void create_characterConfigDepthBoundary() throws Exception {
    int max = CharacterConstraints.CONFIG_MAX_DEPTH;

    create("\"character_config\":" + nestedObjectOfDepth(max)).andExpect(status().isCreated());

    create("\"character_config\":" + nestedObjectOfDepth(max + 1))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALID_400_001"))
        .andExpect(jsonPath("$.message").value(containsString("characterConfig")));
  }

  @Test
  @DisplayName("등록: characterConfig 바이트 수는 원문 공백이 아니라 compact 직렬화로 센다")
  void create_characterConfigCountsCompactBytes() throws Exception {
    // compact로는 4,096바이트지만 원문에 공백을 넣어 더 길게 보낸다 — 통과해야 한다.
    String padded =
        objectOfBytes(CharacterConstraints.CONFIG_MAX_BYTES).replace("\"k\":", "\"k\" :   ");

    create("\"character_config\":" + padded).andExpect(status().isCreated());
  }

  @Test
  @DisplayName("등록: characterConfig에 JSON null을 보내면 값 없음으로 통과하고 파사드에는 null 트리가 아닌 '없음'으로 전달된다")
  void create_characterConfigJsonNull_treatedAsAbsent() throws Exception {
    create("\"character_config\":null,\"character_message\":null").andExpect(status().isCreated());

    ArgumentCaptor<PerformanceCreateRequest> captor =
        ArgumentCaptor.forClass(PerformanceCreateRequest.class);
    then(performanceFacade).should().createPerformance(captor.capture(), any(), any(), any());
    PerformanceCreateRequest request = captor.getValue();
    // JSON null이 NullNode로 바인딩되든 Java null이든, 저장 쪽은 둘 다 "없음"으로 읽는다(매퍼 toJsonString).
    assertThat(request.characterConfig() == null || request.characterConfig().isNull()).isTrue();
  }

  // ---------- 등록: characterMessage ----------

  @Test
  @DisplayName("등록: characterMessage가 50자면 통과하고 51자면 400")
  void create_characterMessageLengthBoundary() throws Exception {
    create("\"character_message\":\"" + "가".repeat(50) + "\"").andExpect(status().isCreated());

    create("\"character_message\":\"" + "가".repeat(51) + "\"")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALID_400_001"))
        .andExpect(jsonPath("$.message").value(containsString("characterMessage")));
  }

  // ---------- 수정 ----------

  @Test
  @DisplayName("수정: characterConfig가 객체가 아니면 400, 4,097바이트면 400, 한마디 51자면 400")
  void patch_invalidCharacterFields_badRequest() throws Exception {
    patchWith("{\"character_config\":\"abc\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALID_400_001"))
        .andExpect(jsonPath("$.message").value(containsString("characterConfig")));

    patchWith(
            "{\"character_config\":"
                + objectOfBytes(CharacterConstraints.CONFIG_MAX_BYTES + 1)
                + "}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALID_400_001"));

    patchWith("{\"character_message\":\"" + "가".repeat(51) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALID_400_001"))
        .andExpect(jsonPath("$.message").value(containsString("characterMessage")));

    then(performanceFacade).should(never()).patchPerformance(any(), any());
  }

  @Test
  @DisplayName("수정: 빈 문자열 한마디(삭제)와 4,096바이트 객체는 통과해 파사드에 그대로 전달된다")
  void patch_emptyMessageAndMaxConfig_ok() throws Exception {
    patchWith(
            "{\"character_message\":\"\",\"character_config\":"
                + objectOfBytes(CharacterConstraints.CONFIG_MAX_BYTES)
                + "}")
        .andExpect(status().isOk());

    ArgumentCaptor<PerformancePatchRequest> captor =
        ArgumentCaptor.forClass(PerformancePatchRequest.class);
    then(performanceFacade).should().patchPerformance(eq(1L), captor.capture());
    assertThat(captor.getValue().characterMessage()).isEmpty();
    assertThat(captor.getValue().characterConfig().isObject()).isTrue();
  }
}

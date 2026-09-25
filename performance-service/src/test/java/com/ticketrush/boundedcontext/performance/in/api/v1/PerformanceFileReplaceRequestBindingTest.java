package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceFileReplaceRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceFileReplaceResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.support.WebMvcSliceTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.multipart.MultipartFile;

/**
 * #688 — 파일 교체의 {@code request} JSON 파트가 컨트롤러 경계에서 어떻게 바인딩되는지 고정한다.
 *
 * <p>레포에서 {@code @RequestPart(required = false)}로 JSON DTO를 받는 첫 자리다. 파트를 보내지 않으면 null로 들어오는지(하위
 * 호환의 전제), 보내면 snake_case 키가 {@code JacksonConfig}대로 풀리는지를 실측으로 남긴다. {@code @WebMvcSliceTest}는 전역
 * {@code JacksonConfig}(SNAKE_CASE·NON_NULL)를 포함하므로 요청 키는 프론트가 실제로 보내는 이름이다.
 */
@WebMvcSliceTest(PerformanceAdminController.class)
@Import(CustomSecurityProperties.class)
@WithMockUser
class PerformanceFileReplaceRequestBindingTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PerformanceFacade performanceFacade;

  private static final String URL = "/api/v1/performance/admin/1/files";

  private static MockMultipartFile requestPart(String json) {
    return new MockMultipartFile(
        "request", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8));
  }

  private ResultActions replace(MockMultipartFile... parts) throws Exception {
    var builder = multipart(HttpMethod.PATCH, URL);
    for (MockMultipartFile part : parts) {
      builder = builder.file(part);
    }
    return mockMvc.perform(builder.with(csrf()));
  }

  private void stubSuccess() {
    given(performanceFacade.replacePerformanceFiles(any(), any(), any(), any(), any()))
        .willReturn(new PerformanceFileReplaceResponse("m", null, List.of()));
  }

  @Test
  @DisplayName("snake_case 키의 request 파트만 보내도 200 이고, 유지 목록·비우기 값이 그대로 파사드에 전달된다")
  void requestPartOnly_bindsSnakeCaseKeys() throws Exception {
    stubSuccess();

    replace(
            requestPart(
                """
                {"keep_gallery_urls":["https://s3.example/g2.png","https://s3.example/g1.png"],
                 "clear_model3d":true}
                """))
        .andExpect(status().isOk())
        // 응답의 null image3dUrl 은 NON_NULL 로 키째 빠진다 — 프론트가 "비워졌다"를 읽는 방식
        .andExpect(jsonPath("$.result.image3d_url").doesNotExist());

    ArgumentCaptor<PerformanceFileReplaceRequest> captor =
        ArgumentCaptor.forClass(PerformanceFileReplaceRequest.class);
    then(performanceFacade)
        .should()
        .replacePerformanceFiles(eq(1L), isNull(), isNull(), isNull(), captor.capture());

    PerformanceFileReplaceRequest request = captor.getValue();
    assertThat(request.keepGalleryUrls())
        .containsExactly("https://s3.example/g2.png", "https://s3.example/g1.png");
    assertThat(request.clearModel3d()).isTrue();
  }

  @Test
  @DisplayName("빈 유지 목록([])은 null 이 아니라 빈 List 로 바인딩된다 — 비우기와 '지시 없음'을 가르는 경계")
  void emptyKeepList_bindsAsEmptyNotNull() throws Exception {
    stubSuccess();

    replace(requestPart("{\"keep_gallery_urls\":[]}")).andExpect(status().isOk());

    ArgumentCaptor<PerformanceFileReplaceRequest> captor =
        ArgumentCaptor.forClass(PerformanceFileReplaceRequest.class);
    then(performanceFacade)
        .should()
        .replacePerformanceFiles(eq(1L), isNull(), isNull(), isNull(), captor.capture());

    assertThat(captor.getValue().keepGalleryUrls()).isNotNull().isEmpty();
    assertThat(captor.getValue().clearModel3d()).isNull();
  }

  @Test
  @DisplayName("request 파트를 보내지 않으면 null 로 전달되고 파일 파트는 그대로 바인딩된다 (하위 호환)")
  void withoutRequestPart_bindsNull() throws Exception {
    stubSuccess();

    replace(new MockMultipartFile("model3d", "m.glb", null, "x".getBytes()))
        .andExpect(status().isOk());

    then(performanceFacade)
        .should()
        .replacePerformanceFiles(eq(1L), isNull(), any(), isNull(), isNull());
  }

  @Test
  @DisplayName("request 파트와 gallery 파일을 한 요청에 함께 보내면 둘 다 제 인자로 바인딩된다 (프론트가 실제로 보낼 조합)")
  void requestPartWithGalleryFiles_bindsBoth() throws Exception {
    stubSuccess();

    replace(
            requestPart("{\"keep_gallery_urls\":[\"https://s3.example/g1.png\"]}"),
            new MockMultipartFile("gallery", "n1.png", null, "x".getBytes()),
            new MockMultipartFile("gallery", "n2.png", null, "y".getBytes()))
        .andExpect(status().isOk());

    ArgumentCaptor<PerformanceFileReplaceRequest> request =
        ArgumentCaptor.forClass(PerformanceFileReplaceRequest.class);
    ArgumentCaptor<List<MultipartFile>> gallery = ArgumentCaptor.forClass(List.class);
    then(performanceFacade)
        .should()
        .replacePerformanceFiles(eq(1L), isNull(), isNull(), gallery.capture(), request.capture());

    assertThat(request.getValue().keepGalleryUrls()).containsExactly("https://s3.example/g1.png");
    assertThat(gallery.getValue())
        .extracting(MultipartFile::getOriginalFilename)
        .containsExactly("n1.png", "n2.png");
  }

  @Test
  @DisplayName("request 파트가 올바른 JSON 이 아니면 파사드에 닿지 않는다 (현재 응답은 500 — 등록 API 와 같은 조건, 범위 밖)")
  void malformedRequestPart_doesNotReachFacade() throws Exception {
    // 문법 오류·타입 불일치 모두 HttpMessageNotReadableException 인데 GlobalExceptionHandler 에 전용 핸들러가 없어
    // catch-all 로 500 이 나간다. 등록 API 의 request 파트도 같은 조건이라 #688 범위 밖으로 두고 현재 동작만 실측으로 기록한다.
    replace(requestPart("{not json")).andExpect(status().isInternalServerError());
    replace(requestPart("{\"clear_model3d\":\"yes\"}")).andExpect(status().isInternalServerError());

    then(performanceFacade)
        .should(never())
        .replacePerformanceFiles(any(), any(), any(), any(), any());
  }
}

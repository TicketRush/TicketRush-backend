package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceFileReplaceResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

@WebMvcTest(PerformanceAdminController.class)
@Import({CustomSecurityProperties.class, JacksonConfig.class, SecurityConfig.class})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class PerformanceAdminControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PerformanceFacade performanceFacade;

  private static final String BASE_URL = "/api/v1/performance/admin";
  private static final String INTERNAL_TOKEN = "test-token";

  @Test
  @DisplayName("관리자 권한으로 공연 목록을 조회하면 200을 반환한다")
  void getAdminPerformances_admin_success() throws Exception {
    given(performanceFacade.getAdminPerformances(any()))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

    mockMvc
        .perform(
            get(BASE_URL)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("일반 사용자 권한으로 공연 목록을 조회하면 403을 반환한다")
  void getAdminPerformances_userRole_forbidden() throws Exception {
    mockMvc
        .perform(
            get(BASE_URL)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("인증 없이 공연 목록을 조회하면 403을 반환한다")
  void getAdminPerformances_noAuth_forbidden() throws Exception {
    mockMvc.perform(get(BASE_URL)).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("관리자 권한으로 공연 삭제 요청 시 200을 반환한다")
  void deletePerformance_admin_success() throws Exception {
    doNothing().when(performanceFacade).deletePerformance(1L);

    mockMvc
        .perform(
            delete(BASE_URL + "/1")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("일반 사용자 권한으로 공연 삭제 요청 시 403을 반환한다")
  void deletePerformance_userRole_forbidden() throws Exception {
    mockMvc
        .perform(
            delete(BASE_URL + "/1")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("인증 없이 공연 삭제 요청 시 403을 반환한다")
  void deletePerformance_noAuth_forbidden() throws Exception {
    mockMvc.perform(delete(BASE_URL + "/1")).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("내부 토큰 없이 관리자 권한으로 공연 삭제 요청 시 403을 반환한다")
  void deletePerformance_missingInternalToken_forbidden() throws Exception {
    mockMvc
        .perform(delete(BASE_URL + "/1").header("X-User-Id", "1").header("X-User-Role", "ADMIN"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("잘못된 내부 토큰과 관리자 권한으로 공연 삭제 요청 시 403을 반환한다")
  void deletePerformance_invalidInternalToken_forbidden() throws Exception {
    mockMvc
        .perform(
            delete(BASE_URL + "/1")
                .header("X-Gateway-Token", "invalid-token")
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isForbidden());
  }

  /**
   * 각 파트가 제 인자로 전달되는지와 응답 필드명을 고정한다.
   *
   * <p>파사드가 {@code MultipartFile} 두 개를 연달아 받으므로 컨트롤러에서 {@code mainImage}와 {@code model3d}를 뒤바꿔 넘겨도
   * <b>컴파일이 통과한다.</b> 그러면 3D 모델이 {@code performances/main} 키에 올라가고 응답의 {@code image3dUrl}이 png를
   * 가리키는데, 상태 코드만 보는 테스트로는 잡히지 않는다.
   *
   * <p>레포의 첫 PATCH 멀티파트 엔드포인트다. MockMvc의 {@code multipart(String)}은 POST로 요청을 만들므로 {@code
   * multipart(HttpMethod.PATCH, ...)} 오버로드를 써야 한다. <b>다만 MockMvc는 서블릿 컨테이너를 거치지 않으므로 이 테스트가 고정하는
   * 범위는 스프링 레벨의 라우팅·바인딩까지다</b> — 실제 톰캣의 PATCH 멀티파트 파싱은 기동 후 왕복으로 확인해야 한다.
   */
  @Test
  @DisplayName("관리자 권한으로 파일 교체를 요청하면 각 파트가 제 인자로 전달되고 새 URL이 응답된다")
  void replacePerformanceFiles_admin_success() throws Exception {
    given(performanceFacade.replacePerformanceFiles(any(), any(), any(), any()))
        .willReturn(
            new PerformanceFileReplaceResponse(
                "https://s3.example/main.png",
                "https://s3.example/model.glb",
                List.of("https://s3.example/g1.png")));

    mockMvc
        .perform(
            multipart(HttpMethod.PATCH, BASE_URL + "/1/files")
                .file(new MockMultipartFile("mainImage", "main.png", null, "main".getBytes()))
                .file(new MockMultipartFile("model3d", "model.glb", null, "model".getBytes()))
                .file(new MockMultipartFile("gallery", "g1.png", null, "g1".getBytes()))
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        // 응답 필드는 SNAKE_CASE로 나간다(JacksonConfig). 프론트가 읽는 이름이므로 여기서 고정한다
        .andExpect(jsonPath("$.result.image_main_url").value("https://s3.example/main.png"))
        .andExpect(jsonPath("$.result.image3d_url").value("https://s3.example/model.glb"))
        .andExpect(jsonPath("$.result.image_gallery_urls[0]").value("https://s3.example/g1.png"));

    ArgumentCaptor<MultipartFile> mainImage = ArgumentCaptor.forClass(MultipartFile.class);
    ArgumentCaptor<MultipartFile> model3d = ArgumentCaptor.forClass(MultipartFile.class);
    ArgumentCaptor<List<MultipartFile>> gallery = ArgumentCaptor.forClass(List.class);

    verify(performanceFacade)
        .replacePerformanceFiles(eq(1L), mainImage.capture(), model3d.capture(), gallery.capture());

    assertThat(mainImage.getValue().getOriginalFilename()).isEqualTo("main.png");
    assertThat(model3d.getValue().getOriginalFilename()).isEqualTo("model.glb");
    assertThat(gallery.getValue())
        .extracting(MultipartFile::getOriginalFilename)
        .containsExactly("g1.png");
  }

  @Test
  @DisplayName("일반 사용자 권한으로 파일 교체를 요청하면 403을 반환한다")
  void replacePerformanceFiles_userRole_forbidden() throws Exception {
    mockMvc
        .perform(
            multipart(HttpMethod.PATCH, BASE_URL + "/1/files")
                .file(new MockMultipartFile("model3d", "new.glb", null, "content".getBytes()))
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("인증 없이 파일 교체를 요청하면 403을 반환한다")
  void replacePerformanceFiles_noAuth_forbidden() throws Exception {
    mockMvc
        .perform(
            multipart(HttpMethod.PATCH, BASE_URL + "/1/files")
                .file(new MockMultipartFile("model3d", "new.glb", null, "content".getBytes())))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("관리자 권한으로 예매 오픈 시각 해제 요청 시 200을 반환한다")
  void clearBookingOpenAt_admin_success() throws Exception {
    doNothing().when(performanceFacade).clearBookingOpenAt(1L);

    mockMvc
        .perform(
            delete(BASE_URL + "/1/booking-open-at")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("일반 사용자 권한으로 예매 오픈 시각 해제 요청 시 403을 반환한다")
  void clearBookingOpenAt_userRole_forbidden() throws Exception {
    mockMvc
        .perform(
            delete(BASE_URL + "/1/booking-open-at")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("인증 없이 예매 오픈 시각 해제 요청 시 403을 반환한다")
  void clearBookingOpenAt_noAuth_forbidden() throws Exception {
    mockMvc.perform(delete(BASE_URL + "/1/booking-open-at")).andExpect(status().isForbidden());
  }
}

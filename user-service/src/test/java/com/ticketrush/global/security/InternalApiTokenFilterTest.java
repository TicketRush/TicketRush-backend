package com.ticketrush.global.security;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketrush.boundedcontext.user.app.dto.request.UserAuthInfoRequest;
import com.ticketrush.boundedcontext.user.app.dto.response.EmailExistsResponse;
import com.ticketrush.boundedcontext.user.app.dto.response.UserAuthInfoResponse;
import com.ticketrush.boundedcontext.user.app.facade.UserFacade;
import com.ticketrush.boundedcontext.user.in.api.v1.InternalUserController;
import com.ticketrush.boundedcontext.user.in.api.v1.UserController;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.support.WebMvcSliceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcSliceTest({InternalUserController.class, UserController.class})
@Import({
  SecurityConfig.class,
  InternalApiTokenFilter.class,
  GatewayHeaderFilter.class,
  CustomSecurityProperties.class
})
@TestPropertySource(
    properties = {
      "custom.security.internal-token=test-internal-token",
      "custom.security.permit-all=false"
    })
class InternalApiTokenFilterTest {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  @Autowired private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private UserFacade userFacade;

  @Test
  @DisplayName("POST 내부 API는 내부 토큰이 없으면 403 Forbidden을 반환한다")
  void postInternalApi_withoutToken_forbidden() throws Exception {
    UserAuthInfoRequest request = new UserAuthInfoRequest("test@test.com");

    mockMvc
        .perform(
            post("/api/v1/internal/user/auth-info")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andDo(print())
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("POST 내부 API는 내부 토큰이 틀리면 403 Forbidden을 반환한다")
  void postInternalApi_withWrongToken_forbidden() throws Exception {
    UserAuthInfoRequest request = new UserAuthInfoRequest("test@test.com");

    mockMvc
        .perform(
            post("/api/v1/internal/user/auth-info")
                .header(INTERNAL_TOKEN_HEADER, "wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andDo(print())
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("POST 내부 API는 내부 토큰이 맞으면 요청을 통과시킨다")
  void postInternalApi_withValidToken_success() throws Exception {
    UserAuthInfoRequest request = new UserAuthInfoRequest("test@test.com");

    given(userFacade.getUserAuthInfoByEmail(anyString()))
        .willReturn(new UserAuthInfoResponse(1L, "test@test.com", "passwordHash", "USER"));

    mockMvc
        .perform(
            post("/api/v1/internal/user/auth-info")
                .header(INTERNAL_TOKEN_HEADER, "test-internal-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andDo(print())
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("GET 내부 API는 내부 토큰이 없으면 403 Forbidden을 반환한다")
  void getInternalApi_withoutToken_forbidden() throws Exception {
    mockMvc
        .perform(get("/api/v1/internal/user/1/auth-info"))
        .andDo(print())
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("GET 내부 API는 내부 토큰이 틀리면 403 Forbidden을 반환한다")
  void getInternalApi_withWrongToken_forbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/internal/user/1/auth-info").header(INTERNAL_TOKEN_HEADER, "wrong-token"))
        .andDo(print())
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("GET 내부 API는 내부 토큰이 맞으면 요청을 통과시킨다")
  void getInternalApi_withValidToken_success() throws Exception {
    given(userFacade.getUserAuthInfoByUserId(anyLong()))
        .willReturn(new UserAuthInfoResponse(1L, "test@test.com", "passwordHash", "USER"));

    mockMvc
        .perform(
            get("/api/v1/internal/user/1/auth-info")
                .header(INTERNAL_TOKEN_HEADER, "test-internal-token"))
        .andDo(print())
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("공개 API는 내부 토큰 없이도 호출 가능하다")
  void publicApi_withoutInternalToken_success() throws Exception {
    given(userFacade.existsByEmail(anyString())).willReturn(new EmailExistsResponse(false));

    mockMvc
        .perform(get("/api/v1/user/exists/email").param("email", "test@test.com"))
        .andExpect(status().isOk());
  }
}

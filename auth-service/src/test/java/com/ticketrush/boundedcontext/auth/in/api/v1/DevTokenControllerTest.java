package com.ticketrush.boundedcontext.auth.in.api.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.auth.app.dto.request.DevTokenIssueRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.LoginResponse;
import com.ticketrush.boundedcontext.auth.app.facade.DevAuthFacade;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.support.WebMvcSliceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("local")
@WebMvcSliceTest(DevTokenController.class)
@Import({SecurityConfig.class, GatewayHeaderFilter.class, CustomSecurityProperties.class})
@TestPropertySource(
    properties = {
      "custom.security.internal-token=test-internal-token",
      "custom.security.permit-all=true",
      "gateway.internal-token=test-internal-token"
    })
class DevTokenControllerTest {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
  private static final String INTERNAL_TOKEN = "test-internal-token";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DevAuthFacade devAuthFacade;

  @Test
  @DisplayName("userId를 전달하면 테스트용 토큰을 발급한다")
  void issueDevToken_success() throws Exception {
    // given
    Long userId = 1L;

    LoginResponse response =
        new LoginResponse(userId, "test@example.com", "USER", "access-token", "refresh-token");

    given(devAuthFacade.issueDevToken(any(DevTokenIssueRequest.class))).willReturn(response);

    // when & then
    mockMvc
        .perform(
            post("/api/v1/dev/auth/token")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
            {
              "user_id": 1
            }
            """))
        .andDo(print())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.code").value("COMMON_200"))
        .andExpect(jsonPath("$.message").value("성공입니다."))
        .andExpect(jsonPath("$.result.user_id").value(userId))
        .andExpect(jsonPath("$.result.email").value("test@example.com"))
        .andExpect(jsonPath("$.result.role").value("USER"))
        .andExpect(jsonPath("$.result.access_token").value("access-token"))
        .andExpect(jsonPath("$.result.refresh_token").value("refresh-token"));
  }

  @Test
  @DisplayName("userId가 없으면 테스트용 토큰 발급 요청에 실패한다")
  void issueDevToken_fail_whenUserIdIsNull() throws Exception {
    // when & then
    mockMvc
        .perform(
            post("/api/v1/dev/auth/token")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andDo(print())
        .andExpect(status().isBadRequest());
  }
}

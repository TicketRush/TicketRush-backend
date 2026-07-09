package com.ticketrush.boundedcontext.auth.in.api.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailVerificationConsumeRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.SignupEmailVerificationCheckResponse;
import com.ticketrush.boundedcontext.auth.app.facade.AuthFacade;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.security.InternalApiTokenFilter;
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
@WebMvcSliceTest(InternalAuthController.class)
@Import({
  SecurityConfig.class,
  InternalApiTokenFilter.class,
  GatewayHeaderFilter.class,
  CustomSecurityProperties.class
})
@TestPropertySource(
    properties = {
      "custom.security.internal-token=test-internal-token",
      "custom.security.permit-all=true",
      "gateway.internal-token=test-internal-token"
    })
class InternalAuthControllerTest {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
  private static final String INTERNAL_TOKEN = "test-internal-token";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AuthFacade authFacade;

  @Test
  @DisplayName("내부 토큰으로 회원가입 이메일 인증 완료 여부를 조회한다")
  void checkSignupEmailVerification_success() throws Exception {
    // given
    String email = "test@example.com";

    SignupEmailVerificationCheckResponse response = new SignupEmailVerificationCheckResponse(true);

    given(authFacade.checkSignupEmailVerification(email)).willReturn(response);

    // when & then
    mockMvc
        .perform(
            get("/api/v1/internal/auth/signup/email-verification/verified")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .param("email", email))
        .andDo(print())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));

    verify(authFacade).checkSignupEmailVerification(email);
  }

  @Test
  @DisplayName("내부 토큰 없이 회원가입 이메일 인증 완료 여부를 조회하면 실패한다")
  void checkSignupEmailVerification_fail_whenInternalTokenMissing() throws Exception {
    // when & then
    mockMvc
        .perform(
            get("/api/v1/internal/auth/signup/email-verification/verified")
                .param("email", "test@example.com"))
        .andDo(print())
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("내부 토큰으로 회원가입 이메일 인증 완료 상태를 소비한다")
  void consumeSignupEmailVerification_success() throws Exception {
    // when & then
    mockMvc
        .perform(
            post("/api/v1/internal/auth/signup/email-verification/consume")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
    {
      "email": "test@example.com"
    }
    """))
        .andDo(print())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));

    verify(authFacade)
        .consumeSignupEmailAuthVerified(any(SignupEmailVerificationConsumeRequest.class));
  }

  @Test
  @DisplayName("내부 토큰 없이 회원가입 이메일 인증 완료 상태를 소비하면 실패한다")
  void consumeSignupEmailVerification_fail_whenInternalTokenMissing() throws Exception {
    // when & then
    mockMvc
        .perform(
            post("/api/v1/internal/auth/signup/email-verification/consume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
    {
      "email": "test@example.com"
    }
    """))
        .andDo(print())
        .andExpect(status().isForbidden());
  }
}

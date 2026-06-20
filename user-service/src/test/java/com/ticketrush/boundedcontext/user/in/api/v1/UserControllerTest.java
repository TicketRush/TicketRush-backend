package com.ticketrush.boundedcontext.user.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.user.app.dto.response.UserMeResponse;
import com.ticketrush.boundedcontext.user.app.facade.UserFacade;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.InternalApiTokenFilter;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.support.WebMvcSliceTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcSliceTest(UserController.class)
@Import({
  SecurityConfig.class,
  InternalApiTokenFilter.class,
  GatewayHeaderFilter.class,
  CustomSecurityProperties.class
})
@TestPropertySource(
    properties = {
      "custom.security.internal-token=test-internal-token",
      "custom.security.permit-all=false",
      "gateway.internal-token=test-internal-token"
    })
class UserControllerTest {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
  private static final String USER_ID_HEADER = "X-User-Id";
  private static final String USER_ROLE_HEADER = "X-User-Role";

  private static final String INTERNAL_TOKEN = "test-internal-token";
  private static final Long USER_ID = 5L;
  private static final String USER_ROLE = "USER";
  private static final String USER_NAME = "테스트유저";
  private static final String USER_EMAIL = "test@test.com";
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 30, 7, 0);

  @Autowired private MockMvc mockMvc;

  @MockitoBean private UserFacade userFacade;

  @Test
  @DisplayName("로그인한 회원은 내 회원 정보를 조회할 수 있다")
  void getMyInfo_success() throws Exception {
    // given
    given(userFacade.getMyInfo(USER_ID))
        .willReturn(new UserMeResponse(USER_NAME, USER_EMAIL, CREATED_AT));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/user/me")
                .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                .header(USER_ID_HEADER, String.valueOf(USER_ID))
                .header(USER_ROLE_HEADER, USER_ROLE))
        .andDo(print())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.code").value("COMMON_200"))
        .andExpect(jsonPath("$.message").value("성공입니다."))
        .andExpect(jsonPath("$.result.name").value(USER_NAME))
        .andExpect(jsonPath("$.result.email").value(USER_EMAIL))
        .andExpect(jsonPath("$.result.created_at").exists());
  }
}

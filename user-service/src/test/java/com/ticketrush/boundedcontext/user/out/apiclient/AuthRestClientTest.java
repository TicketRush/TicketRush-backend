package com.ticketrush.boundedcontext.user.out.apiclient;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AuthRestClientTest {

  private static final String AUTH_SERVICE_URL = "http://auth-service:8082";
  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
  private static final String INTERNAL_TOKEN = "test-internal-token";

  private MockRestServiceServer mockServer;
  private AuthRestClient authRestClient;

  @BeforeEach
  void setUp() {
    RestClient.Builder restClientBuilder = RestClient.builder().baseUrl(AUTH_SERVICE_URL);
    mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

    authRestClient = new AuthRestClient(restClientBuilder.build());
    ReflectionTestUtils.setField(authRestClient, "internalToken", INTERNAL_TOKEN);
  }

  @Test
  @DisplayName("회원가입 이메일 인증 완료 상태 소비 시 internal auth 경로와 내부 토큰을 사용한다")
  void consumeSignupEmailVerification_success() {
    // given
    String email = "test@example.com";

    mockServer
        .expect(
            once(),
            requestTo(AUTH_SERVICE_URL + "/api/v1/internal/auth/signup/email-verification/consume"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
        .andRespond(
            withSuccess(
                """
          {
            "is_success": true,
            "code": "COMMON_200",
            "message": "성공입니다.",
            "result": null
          }
          """,
                MediaType.APPLICATION_JSON));

    // when
    authRestClient.consumeSignupEmailVerification(email);

    // then
    mockServer.verify();
  }
}

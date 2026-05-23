package com.ticketrush.boundedcontext.user.out.apiclient;

import com.ticketrush.boundedcontext.user.app.dto.request.SignupEmailVerificationConsumeRequest;
import com.ticketrush.boundedcontext.user.app.dto.response.AuthApiResponse;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final RestClient authServiceRestClient;

  @Value("${custom.security.internal-token}")
  private String internalToken;

  public void consumeSignupEmailVerification(String email) {
    SignupEmailVerificationConsumeRequest request =
        new SignupEmailVerificationConsumeRequest(email);

    try {
      authServiceRestClient
          .post()
          .uri("/api/v1/auth/signup/email-verification/consume")
          .header(INTERNAL_TOKEN_HEADER, internalToken)
          .body(request)
          .retrieve()
          .body(new ParameterizedTypeReference<AuthApiResponse<Void>>() {});

      log.info("[회원가입 이메일 인증 완료 상태 소비 요청 성공] email={}", email);

    } catch (Exception e) {
      log.warn("[회원가입 이메일 인증 완료 상태 소비 요청 실패] email={}", email, e);
      throw new BusinessException(ErrorStatus.EMAIL_AUTH_NOT_VERIFIED);
    }
  }
}

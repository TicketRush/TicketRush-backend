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
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
  private static final String SIGNUP_EMAIL_VERIFICATION_CONSUME_PATH =
      "/api/v1/internal/auth/signup/email-verification/consume";

  private final RestClient authServiceRestClient;

  @Value("${custom.security.internal-token}")
  private String internalToken;

  public void consumeSignupEmailVerification(String email) {
    SignupEmailVerificationConsumeRequest request =
        new SignupEmailVerificationConsumeRequest(email);

    try {
      AuthApiResponse<Void> response =
          authServiceRestClient
              .post()
              .uri(SIGNUP_EMAIL_VERIFICATION_CONSUME_PATH)
              .header(INTERNAL_TOKEN_HEADER, internalToken)
              .body(request)
              .retrieve()
              .body(new ParameterizedTypeReference<AuthApiResponse<Void>>() {});

      if (response == null || !Boolean.TRUE.equals(response.isSuccess())) {
        log.warn("[회원가입 이메일 인증 완료 상태 소비 응답 실패] email={}, response={}", email, response);
        throw new BusinessException(ErrorStatus.EMAIL_AUTH_NOT_VERIFIED);
      }

      log.info("[회원가입 이메일 인증 완료 상태 소비 요청 성공] email={}", email);

    } catch (RestClientException e) {
      log.warn("[회원가입 이메일 인증 완료 상태 소비 요청 실패] email={}", email, e);
      throw new BusinessException(ErrorStatus.EMAIL_AUTH_NOT_VERIFIED);
    }
  }
}

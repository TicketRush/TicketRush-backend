package com.ticketrush.boundedcontext.user.out.apiclient;

import com.ticketrush.boundedcontext.user.app.dto.response.AuthApiResponse;
import com.ticketrush.boundedcontext.user.app.dto.response.EmailVerificationCheckResponse;
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
public class EmailVerificationClient {

  private final RestClient restClient;

  @Value("${service.auth.base-url}")
  private String authServiceUrl;

  public boolean isVerified(String email) {
    String url = authServiceUrl + "/api/v1/auth/signup/email-verification/verified?email={email}";

    try {
      log.info("[이메일 인증 조회] auth-service 호출 시작 url={}, email={}", url, email);

      AuthApiResponse<EmailVerificationCheckResponse> response =
          restClient
              .get()
              .uri(url, email)
              .retrieve()
              .body(
                  new ParameterizedTypeReference<
                      AuthApiResponse<EmailVerificationCheckResponse>>() {});

      log.info("[이메일 인증 조회] auth-service 응답 response={}", response);

      if (response == null) {
        log.warn("[이메일 인증 조회] auth-service 응답이 null입니다. email={}", email);
        return false;
      }

      log.info(
          "[이메일 인증 조회] 응답 파싱 결과 email={}, isSuccess={}, result={}, verified={}",
          email,
          response.isSuccess(),
          response.result(),
          response.result() != null ? response.result().verified() : null);

      return Boolean.TRUE.equals(response.isSuccess())
          && response.result() != null
          && response.result().verified();

    } catch (RestClientException e) {
      log.error("[이메일 인증 조회] auth-service 호출 실패 email={}, message={}", email, e.getMessage(), e);
      throw new BusinessException(ErrorStatus.EMAIL_VERIFICATION_REQUIRED);
    }
  }
}

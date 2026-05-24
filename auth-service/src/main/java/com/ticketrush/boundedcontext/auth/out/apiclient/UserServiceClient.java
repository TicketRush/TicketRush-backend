package com.ticketrush.boundedcontext.auth.out.apiclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketrush.boundedcontext.auth.app.dto.request.UserServiceSocialLoginRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.UserServiceApiResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.UserServiceAuthInfoResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.UserServiceEmailExistsResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.social.UserServiceSocialLoginResponse;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// auth-service가 user-service를 호출하기 위한 전용 클라이언트
@Slf4j
@Component
@RequiredArgsConstructor
public class UserServiceClient {

  private final RestClient restClient;

  @Value("${service.user-service.base-url}")
  private String userServiceBaseUrl;

  private static final String SOCIAL_LOGIN_PATH = "/api/v1/user/social-login";
  private static final String EMAIL_EXISTS_PATH = "/api/v1/user/exists/email?email={email}";
  private static final String USER_AUTH_INFO_PATH = "/api/v1/user/auth-info?email={email}";

  public UserServiceSocialLoginResponse socialLogin(UserServiceSocialLoginRequest request) {

    log.info("🔥 user-service로 보내는 request = {}", request);
    try {
      log.info("🔥 JSON = {}", new ObjectMapper().writeValueAsString(request));
    } catch (JsonProcessingException e) {
      log.error("JSON 변환 실패", e);
    }

    UserServiceSocialLoginResponse response =
        restClient
            .post()
            .uri(userServiceBaseUrl + SOCIAL_LOGIN_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .onStatus(
                HttpStatusCode::is4xxClientError,
                (req, res) -> {
                  log.error("user-service 4xx 에러 발생: status={}", res.getStatusCode());
                  throw new BusinessException(ErrorStatus.AUTH_USER_BAD_REQUEST);
                })
            .onStatus(
                HttpStatusCode::is5xxServerError,
                (req, res) -> {
                  log.error("user-service 5xx 에러 발생: status={}", res.getStatusCode());
                  throw new BusinessException(ErrorStatus.AUTH_USER_SERVER_ERROR);
                })
            .body(UserServiceSocialLoginResponse.class);

    if (response == null) {
      throw new BusinessException(ErrorStatus.AUTH_USER_COMMUNICATION_FAILED);
    }

    return response;
  }

  // 존재하는 이메일 검증
  public boolean existsByEmail(String email) {
    if (email == null || email.isBlank()) {
      throw new BusinessException(ErrorStatus.AUTH_EMAIL_EXISTS_CHECK_BAD_REQUEST);
    }

    try {
      UserServiceApiResponse<UserServiceEmailExistsResponse> response =
          restClient
              .get()
              .uri(userServiceBaseUrl + EMAIL_EXISTS_PATH, email)
              .retrieve()
              .onStatus(
                  HttpStatusCode::is4xxClientError,
                  (req, res) -> {
                    log.error("user-service 이메일 중복 확인 4xx 에러 발생: status={}", res.getStatusCode());
                    throw new BusinessException(ErrorStatus.AUTH_EMAIL_EXISTS_CHECK_BAD_REQUEST);
                  })
              .onStatus(
                  HttpStatusCode::is5xxServerError,
                  (req, res) -> {
                    log.error("user-service 이메일 중복 확인 5xx 에러 발생: status={}", res.getStatusCode());
                    throw new BusinessException(ErrorStatus.AUTH_EMAIL_EXISTS_CHECK_SERVER_ERROR);
                  })
              .body(
                  new ParameterizedTypeReference<
                      UserServiceApiResponse<UserServiceEmailExistsResponse>>() {});

      if (response == null || response.result() == null || response.result().exists() == null) {
        throw new BusinessException(ErrorStatus.AUTH_EMAIL_EXISTS_CHECK_COMMUNICATION_FAILED);
      }

      return Boolean.TRUE.equals(response.result().exists());

    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("user-service 이메일 중복 확인 통신 실패 email={}", email, e);
      throw new BusinessException(ErrorStatus.AUTH_EMAIL_EXISTS_CHECK_COMMUNICATION_FAILED);
    }
  }

  // 로그인 검증용 회원 정보 조회
  public UserServiceAuthInfoResponse getUserAuthInfoByEmail(String email) {
    if (email == null || email.isBlank()) {
      throw new BusinessException(ErrorStatus.AUTH_LOGIN_BAD_REQUEST);
    }

    try {
      UserServiceApiResponse<UserServiceAuthInfoResponse> response =
          restClient
              .get()
              .uri(userServiceBaseUrl + USER_AUTH_INFO_PATH, email)
              .retrieve()
              .onStatus(
                  HttpStatusCode::is4xxClientError,
                  (req, res) -> {
                    log.error(
                        "user-service 로그인용 회원 정보 조회 4xx 에러 발생: status={}", res.getStatusCode());
                    throw new BusinessException(ErrorStatus.AUTH_LOGIN_FAILED);
                  })
              .onStatus(
                  HttpStatusCode::is5xxServerError,
                  (req, res) -> {
                    log.error(
                        "user-service 로그인용 회원 정보 조회 5xx 에러 발생: status={}", res.getStatusCode());
                    throw new BusinessException(ErrorStatus.AUTH_USER_SERVER_ERROR);
                  })
              .body(
                  new ParameterizedTypeReference<
                      UserServiceApiResponse<UserServiceAuthInfoResponse>>() {});

      if (response == null || response.result() == null) {
        throw new BusinessException(ErrorStatus.AUTH_USER_COMMUNICATION_FAILED);
      }

      return response.result();

    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("user-service 로그인용 회원 정보 조회 통신 실패 email={}", email, e);
      throw new BusinessException(ErrorStatus.AUTH_USER_COMMUNICATION_FAILED);
    }
  }
}

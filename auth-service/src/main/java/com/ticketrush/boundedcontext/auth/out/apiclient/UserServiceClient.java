package com.ticketrush.boundedcontext.auth.out.apiclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketrush.boundedcontext.auth.app.dto.request.UserServiceAuthInfoRequest;
import com.ticketrush.boundedcontext.auth.app.dto.request.UserServiceSocialLoginRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.UserServiceAuthInfoResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.UserServiceApiResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.UserServiceEmailExistsResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.social.UserServiceSocialLoginResponse;
import com.ticketrush.global.config.CustomSecurityProperties;
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
  private final CustomSecurityProperties customSecurityProperties;

  @Value("${service.user.url}")
  private String userServiceBaseUrl;

  private static final String SOCIAL_LOGIN_PATH = "/api/v1/user/social-login";
  private static final String EMAIL_EXISTS_PATH = "/api/v1/user/exists/email?email={email}";
  private static final String USER_AUTH_INFO_PATH = "/api/v1/internal/user/auth-info";
  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
  private static final String USER_AUTH_INFO_BY_USER_ID_PATH =
      "/api/v1/internal/user/{userId}/auth-info";

  public UserServiceSocialLoginResponse socialLogin(UserServiceSocialLoginRequest request) {

    log.info("🔥 user-service로 보내는 request = {}", request);

    try {
      log.info("🔥 JSON = {}", new ObjectMapper().writeValueAsString(request));
    } catch (JsonProcessingException e) {
      log.error("JSON 변환 실패", e);
    }

    try {
      /*
       * user-service 응답은 아래와 같은 ApiResponse 래퍼 구조입니다.
       *
       * {
       *   "is_success": true,
       *   "code": "COMMON_200",
       *   "message": "성공입니다.",
       *   "result": {
       *     "user_id": 1,
       *     "name": "사용자 이름",
       *     "is_new_user": true
       *   }
       * }
       *
       * 따라서 UserServiceSocialLoginResponse로 바로 역직렬화하지 않고,
       * UserServiceApiResponse<UserServiceSocialLoginResponse>로 받은 뒤
       * result를 꺼내야 합니다.
       */
      UserServiceApiResponse<UserServiceSocialLoginResponse> response =
          restClient
              .post()
              .uri(userServiceBaseUrl + SOCIAL_LOGIN_PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .body(request)
              .retrieve()
              .onStatus(
                  HttpStatusCode::is4xxClientError,
                  (req, res) -> {
                    log.error("user-service 소셜 로그인 4xx 에러 발생: status={}", res.getStatusCode());

                    throw new BusinessException(ErrorStatus.AUTH_USER_BAD_REQUEST);
                  })
              .onStatus(
                  HttpStatusCode::is5xxServerError,
                  (req, res) -> {
                    log.error("user-service 소셜 로그인 5xx 에러 발생: status={}", res.getStatusCode());

                    throw new BusinessException(ErrorStatus.AUTH_USER_SERVER_ERROR);
                  })
              .body(
                  new ParameterizedTypeReference<
                      UserServiceApiResponse<UserServiceSocialLoginResponse>>() {});

      if (response == null || response.result() == null) {
        log.error("user-service 소셜 로그인 응답이 비어 있습니다. response={}", response);

        throw new BusinessException(ErrorStatus.AUTH_USER_COMMUNICATION_FAILED);
      }

      return response.result();

    } catch (BusinessException e) {
      throw e;

    } catch (Exception e) {
      log.error("user-service 소셜 로그인 통신 또는 응답 파싱 실패 request={}", request, e);

      throw new BusinessException(ErrorStatus.AUTH_USER_COMMUNICATION_FAILED);
    }
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
      UserServiceAuthInfoRequest request = new UserServiceAuthInfoRequest(email);

      UserServiceApiResponse<UserServiceAuthInfoResponse> response =
          restClient
              .post()
              .uri(userServiceBaseUrl + USER_AUTH_INFO_PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .header(INTERNAL_TOKEN_HEADER, customSecurityProperties.getInternalToken())
              .body(request)
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

  // 테스트 토큰 발급용 회원 정보 조회
  public UserServiceAuthInfoResponse getUserAuthInfoByUserId(Long userId) {
    if (userId == null) {
      throw new BusinessException(ErrorStatus.AUTH_LOGIN_BAD_REQUEST);
    }

    try {
      UserServiceApiResponse<UserServiceAuthInfoResponse> response =
          restClient
              .get()
              .uri(userServiceBaseUrl + USER_AUTH_INFO_BY_USER_ID_PATH, userId)
              .header(INTERNAL_TOKEN_HEADER, customSecurityProperties.getInternalToken())
              .retrieve()
              .onStatus(
                  HttpStatusCode::is4xxClientError,
                  (req, res) -> {
                    log.error(
                        "user-service 테스트 토큰용 회원 정보 조회 4xx 에러 발생: status={}", res.getStatusCode());

                    throw new BusinessException(ErrorStatus.AUTH_LOGIN_FAILED);
                  })
              .onStatus(
                  HttpStatusCode::is5xxServerError,
                  (req, res) -> {
                    log.error(
                        "user-service 테스트 토큰용 회원 정보 조회 5xx 에러 발생: status={}", res.getStatusCode());

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
      log.error("user-service 테스트 토큰용 회원 정보 조회 통신 실패 userId={}", userId, e);

      throw new BusinessException(ErrorStatus.AUTH_USER_COMMUNICATION_FAILED);
    }
  }
}

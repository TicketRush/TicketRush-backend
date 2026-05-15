package com.ticketrush.boundedcontext.auth.out.apiclient;

import com.ticketrush.boundedcontext.auth.app.dto.response.KakaoUserInfoResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.OauthTokenResponse;
import com.ticketrush.boundedcontext.auth.domain.types.SocialProvider;
import com.ticketrush.boundedcontext.auth.domain.types.SocialUserInfo;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOauthApiClient implements SocialOauthApiClient {

  private final RestClient restClient;

  @Value("${oauth.kakao.client-id}")
  private String clientId;

  @Value("${oauth.kakao.client-secret}")
  private String clientSecret;

  @Value("${oauth.kakao.redirect-uri}")
  private String redirectUri;

  @Value("${oauth.kakao.token-uri}")
  private String tokenUri;

  @Value("${oauth.kakao.user-info-uri}")
  private String userInfoUri;

  @Value("${oauth.kakao.auth-uri}")
  private String authUri;

  @Override
  public SocialProvider getProvider() {
    return SocialProvider.KAKAO;
  }

  @Override
  public SocialUserInfo getUserInfo(String code) {
    try {
      OauthTokenResponse tokenResponse = getToken(code);

      if (tokenResponse == null || tokenResponse.accessToken() == null) {
        throw new BusinessException(ErrorStatus.AUTH_KAKAO_TOKEN_FAILED);
      }

      KakaoUserInfoResponse userInfoResponse = getProfile(tokenResponse.accessToken());

      if (userInfoResponse == null || userInfoResponse.id() == null) {
        throw new BusinessException(ErrorStatus.AUTH_KAKAO_INFO_FAILED);
      }

      String socialId = String.valueOf(userInfoResponse.id());
      String nickname =
          userInfoResponse.properties() != null ? userInfoResponse.properties().nickname() : null;

      return new SocialUserInfo(socialId, getProvider(), nickname, null);

    } catch (BusinessException e) {
      throw e;

    } catch (Exception e) {
      log.error("Kakao OAuth 처리 중 예상하지 못한 에러 발생", e);
      throw new BusinessException(ErrorStatus.AUTH_KAKAO_INFO_FAILED);
    }
  }

  @Override
  public String generateOAuthUrl() {

    return UriComponentsBuilder.fromUriString(authUri)
        .queryParam("client_id", clientId)
        .queryParam("redirect_uri", redirectUri)
        .queryParam("response_type", "code")
        .build()
        .encode()
        .toUriString();
  }

  private OauthTokenResponse getToken(String code) {

    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    form.add("redirect_uri", redirectUri);
    form.add("code", code);

    return restClient
        .post()
        .uri(tokenUri)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(form)
        .retrieve()
        .onStatus(
            HttpStatusCode::is4xxClientError,
            (request, response) -> {
              log.warn("Kakao OAuth 토큰 발급 요청 실패 - 클라이언트 오류. status={}", response.getStatusCode());
              throw new BusinessException(ErrorStatus.AUTH_KAKAO_TOKEN_FAILED);
            })
        .onStatus(
            HttpStatusCode::is5xxServerError,
            (request, response) -> {
              log.error("Kakao OAuth 토큰 발급 요청 실패 - 서버 오류. status={}", response.getStatusCode());
              throw new BusinessException(ErrorStatus.AUTH_KAKAO_TOKEN_FAILED);
            })
        .body(OauthTokenResponse.class);
  }

  private KakaoUserInfoResponse getProfile(String accessToken) {

    return restClient
        .get()
        .uri(userInfoUri)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .retrieve()
        .onStatus(
            HttpStatusCode::is4xxClientError,
            (request, response) -> {
              log.warn("Kakao OAuth 사용자 정보 조회 실패 - 클라이언트 오류. status={}", response.getStatusCode());
              throw new BusinessException(ErrorStatus.AUTH_KAKAO_INFO_FAILED);
            })
        .onStatus(
            HttpStatusCode::is5xxServerError,
            (request, response) -> {
              log.error("Kakao OAuth 사용자 정보 조회 실패 - 서버 오류. status={}", response.getStatusCode());
              throw new BusinessException(ErrorStatus.AUTH_KAKAO_INFO_FAILED);
            })
        .body(KakaoUserInfoResponse.class);
  }

  @Override
  public String getDefaultRedirectUri() {
    return redirectUri;
  }
}

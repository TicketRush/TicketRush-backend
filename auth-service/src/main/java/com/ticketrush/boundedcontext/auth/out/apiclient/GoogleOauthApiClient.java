package com.ticketrush.boundedcontext.auth.out.apiclient;

import com.ticketrush.boundedcontext.auth.app.dto.response.GoogleUserInfoResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.OauthTokenResponse;
import com.ticketrush.boundedcontext.auth.domain.types.SocialProvider;
import com.ticketrush.boundedcontext.auth.domain.types.SocialUserInfo;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleOauthApiClient implements SocialOauthApiClient {

  private final RestClient restClient;

  @Value("${oauth.google.client-id}")
  private String clientId;

  @Value("${oauth.google.client-secret}")
  private String clientSecret;

  @Value("${oauth.google.redirect-uri}")
  private String defaultRedirectUri;

  @Value("${oauth.google.auth-uri}")
  private String authUri;

  @Value("${oauth.google.token-uri}")
  private String tokenUri;

  @Value("${oauth.google.user-info-uri}")
  private String userInfoUri;

  @Override
  public SocialProvider getProvider() {
    return SocialProvider.GOOGLE;
  }

  @Override
  public SocialUserInfo getUserInfo(String code) {

    try {

      // 1. 인가 코드로 access token 요청
      OauthTokenResponse tokenResponse = requestToken(code, defaultRedirectUri);

      if (tokenResponse == null || tokenResponse.accessToken() == null) {
        throw new BusinessException(ErrorStatus.AUTH_GOOGLE_TOKEN_FAILED);
      }

      // 2. access token으로 사용자 정보 요청
      GoogleUserInfoResponse userInfoResponse = requestUserInfo(tokenResponse.accessToken());

      if (userInfoResponse == null || userInfoResponse.id() == null) {
        throw new BusinessException(ErrorStatus.AUTH_GOOGLE_INFO_FAILED);
      }

      // 3. 공통 객체로 변환
      return new SocialUserInfo(
          userInfoResponse.id(), getProvider(), userInfoResponse.name(), userInfoResponse.email());

    } catch (BusinessException e) {

      throw e;

    } catch (Exception e) {

      log.error("Google OAuth 처리 중 에러 발생", e);
      throw new BusinessException(ErrorStatus.AUTH_GOOGLE_INFO_FAILED);
    }
  }

  @Override
  public String generateOAuthUrl() {

    return UriComponentsBuilder.fromUriString(authUri)
        .queryParam("client_id", clientId)
        .queryParam("redirect_uri", defaultRedirectUri)
        .queryParam("response_type", "code")
        .queryParam("scope", "profile email")
        .build()
        .toUriString();
  }

  @Override
  public String getDefaultRedirectUri() {
    return defaultRedirectUri;
  }

  private OauthTokenResponse requestToken(String code, String redirectUri) {

    return restClient
        .post()
        .uri(tokenUri)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(
            "code="
                + code
                + "&client_id="
                + clientId
                + "&client_secret="
                + clientSecret
                + "&redirect_uri="
                + redirectUri
                + "&grant_type=authorization_code")
        .retrieve()
        .body(OauthTokenResponse.class);
  }

  private GoogleUserInfoResponse requestUserInfo(String accessToken) {

    return restClient
        .get()
        .uri(userInfoUri)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .retrieve()
        .body(GoogleUserInfoResponse.class);
  }
}

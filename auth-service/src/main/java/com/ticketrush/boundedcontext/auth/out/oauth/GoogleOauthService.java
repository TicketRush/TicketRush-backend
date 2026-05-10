package com.ticketrush.boundedcontext.auth.out.oauth;

import com.ticketrush.boundedcontext.auth.app.dto.response.GoogleUserInfoResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.OauthTokenResponse;
import com.ticketrush.boundedcontext.auth.domain.types.SocialProvider;
import com.ticketrush.boundedcontext.auth.domain.types.SocialUserInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class GoogleOauthService implements SocialOauthService {

  private final RestClient restClient;

  @Value("${oauth.google.client-id}")
  private String clientId;

  @Value("${oauth.google.client-secret}")
  private String clientSecret;

  @Value("${oauth.google.redirect-uri}")
  private String defaultRedirectUri;

  @Override
  public SocialProvider getProvider() {
    return SocialProvider.GOOGLE;
  }

  @Override
  public SocialUserInfo getUserInfo(String code) {

    // 1. 인가 코드로 access token 요청
    OauthTokenResponse tokenResponse = requestToken(code);

    // 2. access token으로 사용자 정보 요청
    GoogleUserInfoResponse userInfoResponse = requestUserInfo(tokenResponse.accessToken());

    // 3. 공통 객체로 변환
    return new SocialUserInfo(
        userInfoResponse.id(), SocialProvider.GOOGLE, userInfoResponse.name());
  }

  @Override
  public String generateOAuthUrl(String redirectUri) {

    String uri = redirectUri != null ? redirectUri : defaultRedirectUri;

    return UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
        .queryParam("client_id", clientId)
        .queryParam("redirect_uri", uri)
        .queryParam("response_type", "code")
        .queryParam("scope", "profile")
        .build()
        .toUriString();
  }

  @Override
  public String getDefaultRedirectUri() {
    return defaultRedirectUri;
  }

  private OauthTokenResponse requestToken(String code) {

    return restClient
        .post()
        .uri("https://oauth2.googleapis.com/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(
            "code="
                + code
                + "&client_id="
                + clientId
                + "&client_secret="
                + clientSecret
                + "&redirect_uri="
                + defaultRedirectUri
                + "&grant_type=authorization_code")
        .retrieve()
        .body(OauthTokenResponse.class);
  }

  private GoogleUserInfoResponse requestUserInfo(String accessToken) {

    return restClient
        .get()
        .uri("https://www.googleapis.com/oauth2/v2/userinfo")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .retrieve()
        .body(GoogleUserInfoResponse.class);
  }
}

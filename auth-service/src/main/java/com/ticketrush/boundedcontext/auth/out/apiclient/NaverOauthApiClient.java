package com.ticketrush.boundedcontext.auth.out.apiclient;

import com.ticketrush.boundedcontext.auth.app.dto.response.NaverUserInfoResponse;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class NaverOauthApiClient implements SocialOauthApiClient {

  private final RestClient restClient;

  @Value("${oauth.naver.client-id}")
  private String clientId;

  @Value("${oauth.naver.client-secret}")
  private String clientSecret;

  @Value("${oauth.naver.redirect-uri}")
  private String defaultRedirectUri;

  @Value("${oauth.naver.authorization-uri}")
  private String authorizationUri;

  @Value("${oauth.naver.token-uri}")
  private String tokenUri;

  @Value("${oauth.naver.user-info-uri}")
  private String userInfoUri;

  @Override
  public SocialProvider getProvider() {
    return SocialProvider.NAVER;
  }

  @Override
  public SocialUserInfo getUserInfo(String code) {

    try {

      // 1. 인가 코드로 access token 요청
      OauthTokenResponse tokenResponse = getToken(code, defaultRedirectUri);

      if (tokenResponse == null || tokenResponse.accessToken() == null) {
        throw new BusinessException(ErrorStatus.AUTH_NAVER_TOKEN_FAILED);
      }

      // 2. access token으로 사용자 정보 요청
      NaverUserInfoResponse userInfoResponse = getProfile(tokenResponse.accessToken());

      if (userInfoResponse == null
          || userInfoResponse.response() == null
          || userInfoResponse.response().id() == null) {

        throw new BusinessException(ErrorStatus.AUTH_NAVER_INFO_FAILED);
      }

      String socialId = userInfoResponse.response().id();
      String nickname = userInfoResponse.response().name();

      return new SocialUserInfo(socialId, getProvider(), nickname);

    } catch (BusinessException e) {

      throw e;

    } catch (Exception e) {

      log.error("Naver OAuth 처리 중 에러 발생", e);
      throw new BusinessException(ErrorStatus.AUTH_NAVER_INFO_FAILED);
    }
  }

  @Override
  public String generateOAuthUrl() {

    log.info("NAVER clientId = {}", clientId);
    log.info("NAVER redirectUri = {}", defaultRedirectUri);

    return UriComponentsBuilder.fromUriString(authorizationUri)
        .queryParam("response_type", "code")
        .queryParam("client_id", clientId)
        .queryParam("redirect_uri", defaultRedirectUri)
        .queryParam("state", "test")
        .build()
        .encode()
        .toUriString();
  }

  private OauthTokenResponse getToken(String code, String redirectUri) {

    log.info("NAVER token 요청 redirectUri = {}", redirectUri);

    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

    form.add("grant_type", "authorization_code");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    form.add("redirect_uri", redirectUri);
    form.add("code", code);
    form.add("state", "test");

    return restClient
        .post()
        .uri(tokenUri)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(form)
        .retrieve()
        .body(OauthTokenResponse.class);
  }

  private NaverUserInfoResponse getProfile(String accessToken) {

    return restClient
        .get()
        .uri(userInfoUri)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .retrieve()
        .body(NaverUserInfoResponse.class);
  }

  @Override
  public String getDefaultRedirectUri() {
    return defaultRedirectUri;
  }
}

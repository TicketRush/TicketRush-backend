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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

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

      return new SocialUserInfo(socialId, getProvider(), nickname);

    } catch (BusinessException e) {

      throw e;
    } catch (Exception e) {
      log.error("Kakao OAuth 처리 중 에러 발생", e);
      throw new BusinessException(ErrorStatus.AUTH_KAKAO_INFO_FAILED);
    }
  }

  @Override
  public String generateOAuthUrl() {

    return "https://kauth.kakao.com/oauth/authorize"
        + "?client_id="
        + clientId
        + "&redirect_uri="
        + redirectUri
        + "&response_type=code";
  }

  private OauthTokenResponse getToken(String code) {

    log.info("clientId = {}", clientId);
    log.info("clientSecret = {}", clientSecret);
    log.info("redirectUri = {}", redirectUri);

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
        .body(OauthTokenResponse.class);
  }

  private KakaoUserInfoResponse getProfile(String accessToken) {
    return restClient
        .get()
        .uri(userInfoUri)
        .header("Authorization", "Bearer " + accessToken)
        .retrieve()
        .body(KakaoUserInfoResponse.class);
  }

  @Override
  public String getDefaultRedirectUri() {
    return redirectUri;
  }
}

package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.app.dto.request.SocialOauthLoginRequest;
import com.ticketrush.boundedcontext.auth.app.dto.request.UserServiceSocialLoginRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.UserServiceAuthInfoResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.social.OauthLoginResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.social.UserServiceSocialLoginResponse;
import com.ticketrush.boundedcontext.auth.domain.types.SocialUserInfo;
import com.ticketrush.boundedcontext.auth.out.apiclient.SocialOauthApiClient;
import com.ticketrush.boundedcontext.auth.out.apiclient.SocialOauthApiClientFactory;
import com.ticketrush.boundedcontext.auth.out.apiclient.UserServiceClient;
import com.ticketrush.boundedcontext.auth.out.repository.RedisRepository;
import com.ticketrush.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
/*
1. provider에 맞는 OAuth 서비스 선택
2. 인가 코드로 소셜 사용자 정보 조회
3. user-service에 회원 식별/생성 요청
4. 사용자의 실제 권한 조회
5. JWT 생성 및 최종 응답 반환
 */
public class SocialOauthLoginUseCase {

  private final SocialOauthApiClientFactory socialOauthApiClientFactory;
  private final UserServiceClient userServiceClient;
  private final JwtTokenProvider jwtTokenProvider;
  private final RedisRepository redisRepository;

  public OauthLoginResponse execute(SocialOauthLoginRequest request) {

    // 1. OAuth 사용자 정보 조회
    SocialOauthApiClient oauthClient = socialOauthApiClientFactory.getClient(request.provider());

    SocialUserInfo socialUserInfo = oauthClient.getUserInfo(request.code());

    // 2. user-service에 소셜 회원 식별 또는 생성 요청
    UserServiceSocialLoginResponse userResponse =
        userServiceClient.socialLogin(
            new UserServiceSocialLoginRequest(
                socialUserInfo.socialId(),
                socialUserInfo.socialProvider().name(),
                socialUserInfo.name(),
                socialUserInfo.email()));

    Long userId = userResponse.userId();

    // 3. 사용자 실제 권한 조회
    UserServiceAuthInfoResponse userAuthInfo = userServiceClient.getUserAuthInfoByUserId(userId);

    String role = validateRole(userAuthInfo.role());

    // 4. JWT 생성
    String accessToken = jwtTokenProvider.createAccessToken(userId, role);

    String refreshToken = jwtTokenProvider.createRefreshToken(userId);

    // 5. Redis에 Refresh Token 저장
    redisRepository.saveRefreshToken(
        userId, refreshToken, jwtTokenProvider.getRefreshTokenExpiration());

    // 6. 응답 반환
    return new OauthLoginResponse(
        userId,
        userResponse.name(),
        userResponse.isNewUser(),
        accessToken,
        refreshToken,
        jwtTokenProvider.getAccessTokenExpiration(),
        jwtTokenProvider.getRefreshTokenExpiration());
  }

  private String validateRole(String role) {
    if (!"MEMBER".equals(role) && !"ADMIN".equals(role)) {
      throw new IllegalArgumentException("지원하지 않는 사용자 역할입니다: " + role);
    }

    return role;
  }
}

package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.app.dto.request.SocialOauthLoginRequest;
import com.ticketrush.boundedcontext.auth.app.dto.request.UserServiceSocialLoginRequest;
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
4. 최종 응답 반환
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

    // 2. user-service 호출
    UserServiceSocialLoginResponse userResponse =
        userServiceClient.socialLogin(
            new UserServiceSocialLoginRequest(
                socialUserInfo.socialId(),
                socialUserInfo.socialProvider().name(),
                socialUserInfo.name(),
                socialUserInfo.email()));

    Long userId = userResponse.userId();

    // 3. JWT 생성
    String accessToken = jwtTokenProvider.createAccessToken(userId, "USER");

    String refreshToken = jwtTokenProvider.createRefreshToken(userId);

    // 4. Redis 저장
    redisRepository.saveRefreshToken(
        userId, refreshToken, jwtTokenProvider.getRefreshTokenExpiration());

    // 5. 응답 반환
    return new OauthLoginResponse(
        userId,
        userResponse.name(),
        userResponse.isNewUser(),
        accessToken,
        refreshToken,
        jwtTokenProvider.getAccessTokenExpiration(),
        jwtTokenProvider.getRefreshTokenExpiration());
  }
}

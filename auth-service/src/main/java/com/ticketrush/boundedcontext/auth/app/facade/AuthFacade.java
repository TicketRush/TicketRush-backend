package com.ticketrush.boundedcontext.auth.app.facade;

import com.ticketrush.boundedcontext.auth.app.dto.request.SocialOauthLoginRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.OauthLoginResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.TokenReissueResponse;
import com.ticketrush.boundedcontext.auth.app.support.ProviderParser;
import com.ticketrush.boundedcontext.auth.app.usecase.OauthLoginUrlUseCase;
import com.ticketrush.boundedcontext.auth.app.usecase.SocialLogoutUseCase;
import com.ticketrush.boundedcontext.auth.app.usecase.SocialOauthLoginUseCase;
import com.ticketrush.boundedcontext.auth.app.usecase.TokenReissueUseCase;
import com.ticketrush.boundedcontext.auth.domain.types.SocialProvider;
import com.ticketrush.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthFacade {

  private final SocialOauthLoginUseCase socialOauthLoginUseCase;
  private final OauthLoginUrlUseCase oauthLoginUrlUseCase;
  private final TokenReissueUseCase tokenReissueUseCase;
  private final SocialLogoutUseCase socialLogoutUseCase;
  private final JwtTokenProvider jwtTokenProvider;
  private final ProviderParser providerParser;

  // OAuth 로그인 URL 생성
  public String getOAuthLoginUrl(String provider, String redirectUri) {
    SocialProvider socialProvider = providerParser.parse(provider);
    return oauthLoginUrlUseCase.generateOAuthUrl(socialProvider, redirectUri);
  }

  // 소셜 로그인 + JWT 발급
  public OauthLoginResponse socialLogin(SocialOauthLoginRequest request) {
    return socialOauthLoginUseCase.execute(request);
  }

  // JWT 재발급
  public TokenReissueResponse reissue(String refreshToken) {
    return tokenReissueUseCase.execute(refreshToken);
  }

  // 로그아웃
  public void logout(Long userId) {
    socialLogoutUseCase.execute(userId);
  }
}

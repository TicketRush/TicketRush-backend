package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.app.dto.response.login.UserServiceAuthInfoResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.social.TokenReissueResponse;
import com.ticketrush.boundedcontext.auth.out.apiclient.UserServiceClient;
import com.ticketrush.boundedcontext.auth.out.repository.RedisRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.security.JwtTokenProvider;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TokenReissueUseCase {

  private final JwtTokenProvider jwtTokenProvider;
  private final RedisRepository redisRepository;
  private final UserServiceClient userServiceClient;

  public TokenReissueResponse execute(String refreshToken) {

    // 1. Refresh Token 검증
    if (!jwtTokenProvider.validateToken(refreshToken)) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_REFRESH_TOKEN);
    }

    // 2. Refresh Token에서 userId 추출
    Long userId = jwtTokenProvider.getUserId(refreshToken);

    // 3. Redis에 저장된 Refresh Token과 일치하는지 검증
    if (!redisRepository.isValidRefreshToken(userId, refreshToken)) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_REFRESH_TOKEN);
    }

    // 4. user-service에서 현재 사용자 권한 조회
    UserServiceAuthInfoResponse user = userServiceClient.getUserAuthInfoByUserId(userId);

    String role = validateRole(user.role());

    // 5. 현재 사용자 권한으로 새 토큰 발급
    String newAccessToken = jwtTokenProvider.createAccessToken(userId, role);

    String newRefreshToken = jwtTokenProvider.createRefreshToken(userId);

    // 6. Redis의 Refresh Token 갱신
    redisRepository.saveRefreshToken(
        userId, newRefreshToken, jwtTokenProvider.getRefreshTokenExpiration());

    // 7. 응답 반환
    return new TokenReissueResponse(
        newAccessToken,
        newRefreshToken,
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

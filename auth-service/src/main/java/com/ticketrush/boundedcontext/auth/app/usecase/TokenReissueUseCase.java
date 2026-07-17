package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.app.dto.response.social.TokenReissueResponse;
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

  public TokenReissueResponse execute(String refreshToken) {

    // 1. refresh token 검증
    if (!jwtTokenProvider.validateToken(refreshToken)) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_REFRESH_TOKEN);
    }

    // 2. userId 추출
    Long userId = jwtTokenProvider.getUserId(refreshToken);

    // 3. Redis 검증
    if (!redisRepository.isValidRefreshToken(userId, refreshToken)) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_REFRESH_TOKEN);
    }

    // 4. 새 토큰 발급
    String newAccessToken = jwtTokenProvider.createAccessToken(userId, "USER");
    String newRefreshToken = jwtTokenProvider.createRefreshToken(userId);

    // 5. Redis 갱신
    redisRepository.saveRefreshToken(
        userId, newRefreshToken, jwtTokenProvider.getRefreshTokenExpiration());

    // 6. 응답
    return new TokenReissueResponse(
        newAccessToken,
        newRefreshToken,
        jwtTokenProvider.getAccessTokenExpiration(),
        jwtTokenProvider.getRefreshTokenExpiration());
  }
}

package com.ticketrush.boundedcontext.auth.out.repository;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisRepository {

  private final RedisTemplate<String, String> redisTemplate;

  // 저장
  public void saveRefreshToken(Long userId, String refreshToken, long expiration) {
    String key = "RT:" + userId;
    redisTemplate.opsForValue().set(key, refreshToken, Duration.ofMillis(expiration));
  }

  // 조회
  public String getRefreshToken(Long userId) {
    return redisTemplate.opsForValue().get("RT:" + userId);
  }

  // 삭제 (로그아웃)
  public void deleteRefreshToken(Long userId) {
    redisTemplate.delete("RT:" + userId);
  }

  // 검증
  public boolean isValidRefreshToken(Long userId, String refreshToken) {
    String saved = getRefreshToken(userId);

    log.debug("요청 토큰 길이 = {}", refreshToken != null ? refreshToken.length() : 0);
    log.debug("저장 토큰 길이 = {}", saved != null ? saved.length() : 0);

    return saved != null && saved.equals(refreshToken);
  }
}

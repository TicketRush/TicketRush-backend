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

  private static final String REFRESH_TOKEN_PREFIX = "RT:";

  private static final String SIGNUP_EMAIL_AUTH_NUMBER_PREFIX = "SIGNUP:EMAIL:AUTH_NUMBER:";
  private static final String SIGNUP_EMAIL_AUTH_COOLDOWN_PREFIX = "SIGNUP:EMAIL:AUTH_COOLDOWN:";
  private static final String SIGNUP_EMAIL_AUTH_VERIFIED_PREFIX = "SIGNUP:EMAIL:AUTH_VERIFIED:";
  private static final String SIGNUP_EMAIL_AUTH_VERIFY_ATTEMPT_PREFIX =
      "SIGNUP:EMAIL:AUTH_VERIFY_ATTEMPT:";

  // 인증번호 유효시간 = 5분
  private static final Duration SIGNUP_EMAIL_AUTH_NUMBER_TTL = Duration.ofMinutes(5);

  // 인증번호 재발송 쿨다운 = 60초
  private static final Duration SIGNUP_EMAIL_AUTH_COOLDOWN_TTL = Duration.ofSeconds(60);

  // 이메일 인증 완료 상태 유효시간 = 30분
  private static final Duration SIGNUP_EMAIL_AUTH_VERIFIED_TTL = Duration.ofMinutes(30);

  // 인증번호 검증 실패 횟수 유효시간 = 5분
  private static final Duration SIGNUP_EMAIL_AUTH_VERIFY_ATTEMPT_TTL = Duration.ofMinutes(5);

  // 토큰 저장
  public void saveRefreshToken(Long userId, String refreshToken, long expiration) {
    String key = REFRESH_TOKEN_PREFIX + userId;
    redisTemplate.opsForValue().set(key, refreshToken, Duration.ofMillis(expiration));
  }

  // 조회
  public String getRefreshToken(Long userId) {
    return redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + userId);
  }

  // 삭제 (로그아웃)
  public void deleteRefreshToken(Long userId) {
    redisTemplate.delete(REFRESH_TOKEN_PREFIX + userId);
  }

  // 검증
  public boolean isValidRefreshToken(Long userId, String refreshToken) {
    String saved = getRefreshToken(userId);

    log.debug("요청 토큰 길이 = {}", refreshToken != null ? refreshToken.length() : 0);
    log.debug("저장 토큰 길이 = {}", saved != null ? saved.length() : 0);

    return saved != null && saved.equals(refreshToken);
  }

  // 회원가입 이메일 인증번호 저장
  public void saveSignupEmailAuthNumber(String email, String authNumber) {
    String key = SIGNUP_EMAIL_AUTH_NUMBER_PREFIX + email;

    redisTemplate.opsForValue().set(key, authNumber, SIGNUP_EMAIL_AUTH_NUMBER_TTL);
  }

  // 발송 실패 시 인증번호 삭제
  public void deleteSignupEmailAuthNumber(String email) {
    String key = SIGNUP_EMAIL_AUTH_NUMBER_PREFIX + email;
    redisTemplate.delete(key);
  }

  // 회원가입 이메일 인증번호 재발송 쿨다운 저장
  public boolean saveSignupEmailAuthCooldownIfAbsent(String email) {
    String key = SIGNUP_EMAIL_AUTH_COOLDOWN_PREFIX + email;

    Boolean saved =
        redisTemplate.opsForValue().setIfAbsent(key, "1", SIGNUP_EMAIL_AUTH_COOLDOWN_TTL);

    return Boolean.TRUE.equals(saved);
  }

  // 이메일 발송 실패 시 쿨다운 삭제
  public void deleteSignupEmailAuthCooldown(String email) {
    String key = SIGNUP_EMAIL_AUTH_COOLDOWN_PREFIX + email;
    redisTemplate.delete(key);
  }

  // 회원가입 이메일 인증번호 조회
  public String getSignupEmailAuthNumber(String email) {
    String key = SIGNUP_EMAIL_AUTH_NUMBER_PREFIX + email;
    return redisTemplate.opsForValue().get(key);
  }

  // 회원가입 이메일 인증 완료 상태 저장
  public void saveSignupEmailAuthVerified(String email) {
    String key = SIGNUP_EMAIL_AUTH_VERIFIED_PREFIX + email;

    redisTemplate.opsForValue().set(key, "true", SIGNUP_EMAIL_AUTH_VERIFIED_TTL);
  }

  // 회원가입 이메일 인증번호 검증 실패 횟수 증가
  public long increaseSignupEmailAuthVerifyAttempt(String email) {
    String key = SIGNUP_EMAIL_AUTH_VERIFY_ATTEMPT_PREFIX + email;

    Long attemptCount = redisTemplate.opsForValue().increment(key);

    if (attemptCount != null && attemptCount == 1L) {
      redisTemplate.expire(key, SIGNUP_EMAIL_AUTH_VERIFY_ATTEMPT_TTL);
    }

    return attemptCount == null ? 0L : attemptCount;
  }

  // 회원가입 이메일 인증번호 검증 실패 횟수 조회
  public int getSignupEmailAuthVerifyAttemptCount(String email) {
    String key = SIGNUP_EMAIL_AUTH_VERIFY_ATTEMPT_PREFIX + email;
    String attemptCount = redisTemplate.opsForValue().get(key);

    if (attemptCount == null) {
      return 0;
    }

    return Integer.parseInt(attemptCount);
  }

  // 회원가입 이메일 인증번호 검증 실패 횟수 삭제
  public void deleteSignupEmailAuthVerifyAttempt(String email) {
    String key = SIGNUP_EMAIL_AUTH_VERIFY_ATTEMPT_PREFIX + email;
    redisTemplate.delete(key);
  }

  // 회원가입 이메일 인증 완료 여부 조회
  public boolean isSignupEmailAuthVerified(String email) {
    String key = SIGNUP_EMAIL_AUTH_VERIFIED_PREFIX + email;

    return Boolean.TRUE.equals(redisTemplate.hasKey(key));
  }

  public boolean consumeSignupEmailAuthVerified(String email) {
    String key = SIGNUP_EMAIL_AUTH_VERIFIED_PREFIX + email;

    Boolean deleted = redisTemplate.delete(key);

    return Boolean.TRUE.equals(deleted);
  }

  // 회원가입 완료 후 이메일 인증 완료 상태 삭제
  public void deleteSignupEmailAuthVerified(String email) {
    String key = SIGNUP_EMAIL_AUTH_VERIFIED_PREFIX + email;

    redisTemplate.delete(key);
  }
}

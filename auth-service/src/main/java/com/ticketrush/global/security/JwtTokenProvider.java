package com.ticketrush.global.security;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JwtTokenProvider {

  private final SecretKey key;
  private final long accessTokenExpiration;
  private final long refreshTokenExpiration;

  public JwtTokenProvider(
      @Value("${jwt.secret}") String secret,
      @Value("${jwt.access-token-expiration}") long accessTokenExpiration,
      @Value("${jwt.refresh-token-expiration}") long refreshTokenExpiration) {

    if (secret.length() < 32) {
      throw new IllegalArgumentException("JWT secret key는 최소 32자 이상이어야 합니다.");
    }

    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.accessTokenExpiration = accessTokenExpiration;
    this.refreshTokenExpiration = refreshTokenExpiration;
  }

  // AccessToken 생성
  public String createAccessToken(Long userId, String role) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + accessTokenExpiration);

    return Jwts.builder()
        .subject(String.valueOf(userId))
        .claim("role", role)
        .claim("type", "access")
        .issuedAt(now)
        .expiration(expiry)
        .signWith(key)
        .compact();
  }

  // refreshToken 생성
  public String createRefreshToken(Long userId) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + refreshTokenExpiration);

    return Jwts.builder()
        .subject(String.valueOf(userId))
        .claim("type", "refresh")
        .issuedAt(now)
        .expiration(expiry)
        .signWith(key)
        .compact();
  }

  public long getAccessTokenExpiration() {
    return accessTokenExpiration;
  }

  public long getRefreshTokenExpiration() {
    return refreshTokenExpiration;
  }

  public boolean validateToken(String token) {
    try {
      Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
      return true;
    } catch (ExpiredJwtException e) {

      throw new BusinessException(ErrorStatus.AUTH_EXPIRED_TOKEN);

    } catch (JwtException | IllegalArgumentException e) {
      log.warn("JWT 검증 실패: {}", e.getClass().getSimpleName());
      throw new BusinessException(ErrorStatus.AUTH_INVALID_TOKEN);
    }
  }

  public Claims getClaims(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }

  public Long getUserId(String token) {
    try {
      return Long.valueOf(getClaims(token).getSubject());
    } catch (JwtException | IllegalArgumentException e) {
      throw new BusinessException(ErrorStatus.UNAUTHORIZED);
    }
  }

  public String getRole(String token) {
    return getClaims(token).get("role", String.class);
  }

  public long getRemainingTime(String token) {
    try {
      Date expiration = getClaims(token).getExpiration();
      return expiration.getTime() - System.currentTimeMillis();

    } catch (ExpiredJwtException e) {
      // 이미 만료 → 블랙리스트 의미 없음
      return 0L;

    } catch (JwtException | IllegalArgumentException e) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_ACCESS_TOKEN);
    }
  }
}

package com.ticketrush.boundedcontext.ticket.domain.policy;

import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 입장권 QR payload를 조회 시점에 JWT 서명 토큰으로 동적 생성한다. DB에는 비밀 평문을 저장하지 않으며, 짧은 만료(ttl)를 두어 캡처/재사용 위험을 줄인다.
 * 인증용 JWT와는 별도 시크릿을 사용한다.
 */
@Component
public class TicketQrPayloadGenerator {

  private final SecretKey key;
  private final long ttlMillis;

  public TicketQrPayloadGenerator(
      @Value("${ticket.qr.secret}") String secret,
      @Value("${ticket.qr.ttl-millis:300000}") long ttlMillis) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalArgumentException("ticket.qr.secret 은 최소 32바이트 이상이어야 합니다.");
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.ttlMillis = ttlMillis;
  }

  public QrPayload generate(Ticket ticket) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + ttlMillis);

    String token =
        Jwts.builder()
            .claim("tid", ticket.getId())
            .claim("bid", ticket.getBookingId())
            .claim("st", ticket.getTicketStatus().name())
            .issuedAt(now)
            .expiration(expiry)
            .signWith(key)
            .compact();

    // issuedAt(Ticket.createdAt)이 JPA Auditing의 LocalDateTime.now()로 JVM 기본 타임존 기준 생성되므로,
    // expiresAt도 동일하게 systemDefault 기준으로 변환해 두 시각의 기준을 일치시킨다. JVM 타임존 표준화는 전 서비스 공통 인프라 사안.
    LocalDateTime expiresAt = LocalDateTime.ofInstant(expiry.toInstant(), ZoneId.systemDefault());
    return new QrPayload(token, expiresAt);
  }
}

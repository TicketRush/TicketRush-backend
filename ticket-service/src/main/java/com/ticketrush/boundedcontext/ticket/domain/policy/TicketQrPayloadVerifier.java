package com.ticketrush.boundedcontext.ticket.domain.policy;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 스캔된 QR payload(JWT)의 서명·만료를 검증하고 입장권 식별자(tid)를 추출한다. {@link TicketQrPayloadGenerator}와 동일한 시크릿을
 * 사용해 위·변조를 차단하며, 만료(TTL 경과)와 그 외 서명/형식 오류를 서로 다른 에러로 구분한다.
 */
@Component
public class TicketQrPayloadVerifier {

  private final SecretKey key;

  public TicketQrPayloadVerifier(@Value("${ticket.qr.secret}") String secret) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalArgumentException("ticket.qr.secret 은 최소 32바이트 이상이어야 합니다.");
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  public VerifiedQrClaims verify(String token) {
    try {
      Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
      Long ticketId = ((Number) claims.get("tid")).longValue();
      return new VerifiedQrClaims(ticketId);
    } catch (ExpiredJwtException e) {
      throw new BusinessException(ErrorStatus.TICKET_QR_EXPIRED);
    } catch (JwtException
        | IllegalArgumentException
        | ClassCastException
        | NullPointerException e) {
      throw new BusinessException(ErrorStatus.TICKET_QR_INVALID);
    }
  }
}

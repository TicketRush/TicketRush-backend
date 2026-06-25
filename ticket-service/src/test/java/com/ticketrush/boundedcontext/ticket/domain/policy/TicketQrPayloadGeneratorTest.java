package com.ticketrush.boundedcontext.ticket.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TicketQrPayloadGeneratorTest {

  private static final String SECRET = "ticketrush-qr-test-secret-key-0123456789";

  private final TicketQrPayloadGenerator generator = new TicketQrPayloadGenerator(SECRET, 300_000L);

  private Ticket ticket() {
    Ticket ticket =
        Ticket.builder()
            .bookingId(100L)
            .ticketTokenHash("hash")
            .ticketStatus(TicketStatus.UNUSED)
            .build();
    ReflectionTestUtils.setField(ticket, "id", 1L);
    return ticket;
  }

  @Test
  @DisplayName("성공: 서명된 JWT payload에 ticketId/bookingId/status 클레임과 만료가 담긴다")
  void generate_signs_payload_with_claims() {
    // when
    QrPayload result = generator.generate(ticket());

    // then
    SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    Claims claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(result.payload()).getPayload();

    assertThat(((Number) claims.get("tid")).longValue()).isEqualTo(1L);
    assertThat(((Number) claims.get("bid")).longValue()).isEqualTo(100L);
    assertThat(claims.get("st", String.class)).isEqualTo("UNUSED");
    assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    assertThat(result.expiresAt()).isAfter(LocalDateTime.now());
  }

  @Test
  @DisplayName("실패: 시크릿이 32바이트 미만이면 생성기 구성에 실패한다")
  void constructor_fails_when_secret_too_short() {
    assertThatThrownBy(() -> new TicketQrPayloadGenerator("short-secret", 300_000L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

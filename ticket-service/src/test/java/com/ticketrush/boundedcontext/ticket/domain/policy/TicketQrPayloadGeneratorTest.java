package com.ticketrush.boundedcontext.ticket.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketQrResponse;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.global.config.JacksonConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ResourceLock("java.util.TimeZone.default")
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
    assertThat(result.expiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
  }

  @Test
  @DisplayName("실패: 시크릿이 32바이트 미만이면 생성기 구성에 실패한다")
  void constructor_fails_when_secret_too_short() {
    assertThatThrownBy(() -> new TicketQrPayloadGenerator("short-secret", 300_000L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  @DisplayName("JWT 만료와 응답 만료는 같은 UTC 초이며 TTL은 5분이다")
  void jwtExpiryMatchesUtcResponse(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);
      Instant before = Instant.now();
      QrPayload result = generator.generate(ticket());
      Instant after = Instant.now();
      SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
      Claims claims =
          Jwts.parser().verifyWith(key).build().parseSignedClaims(result.payload()).getPayload();
      assertThat(claims.getIssuedAt().toInstant())
          .isBetween(before.truncatedTo(ChronoUnit.SECONDS), after);
      assertThat(claims.getExpiration().toInstant())
          .isEqualTo(claims.getIssuedAt().toInstant().plusSeconds(300));
      assertThat(result.expiresAt().toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS))
          .isEqualTo(claims.getExpiration().toInstant());
      JsonMapper.Builder builder = JsonMapper.builder();
      new JacksonConfig().jacksonCustomizer().customize(builder);
      JsonMapper mapper = builder.build();
      TicketQrResponse response =
          new TicketQrResponse(
              result.payload(),
              TicketStatus.UNUSED,
              LocalDateTime.of(2025, 1, 1, 0, 0),
              result.expiresAt());
      String wire = mapper.readTree(mapper.writeValueAsString(response)).get("expires_at").asText();
      assertThat(Instant.parse(wire)).isEqualTo(claims.getExpiration().toInstant());
    } finally {
      TimeZone.setDefault(original);
    }
  }
}

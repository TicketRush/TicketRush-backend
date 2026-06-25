package com.ticketrush.boundedcontext.ticket.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TicketQrPayloadVerifierTest {

  private static final String SECRET = "ticketrush-qr-test-secret-key-0123456789";
  private static final String OTHER_SECRET = "ticketrush-qr-other-secret-key-9876543210";

  private final TicketQrPayloadGenerator generator = new TicketQrPayloadGenerator(SECRET, 300_000L);
  private final TicketQrPayloadVerifier verifier = new TicketQrPayloadVerifier(SECRET);

  private Ticket ticket(Long id) {
    Ticket ticket =
        Ticket.builder()
            .bookingId(100L)
            .ticketTokenHash("hash")
            .ticketStatus(TicketStatus.UNUSED)
            .build();
    ReflectionTestUtils.setField(ticket, "id", id);
    return ticket;
  }

  @Test
  @DisplayName("성공: 유효한 QR 토큰을 검증하면 입장권 ID(tid)를 추출한다")
  void verify_returns_ticket_id() {
    // given
    String token = generator.generate(ticket(7L)).payload();

    // when
    VerifiedQrClaims claims = verifier.verify(token);

    // then
    assertThat(claims.ticketId()).isEqualTo(7L);
  }

  @Test
  @DisplayName("실패: 다른 시크릿으로 서명된 토큰은 TICKET_QR_INVALID를 던진다")
  void verify_fails_when_signature_mismatch() {
    // given
    TicketQrPayloadGenerator forgedGenerator = new TicketQrPayloadGenerator(OTHER_SECRET, 300_000L);
    String forgedToken = forgedGenerator.generate(ticket(1L)).payload();

    // when & then
    assertThatThrownBy(() -> verifier.verify(forgedToken))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_QR_INVALID);
  }

  @Test
  @DisplayName("실패: 형식이 깨진 토큰은 TICKET_QR_INVALID를 던진다")
  void verify_fails_when_malformed() {
    // when & then
    assertThatThrownBy(() -> verifier.verify("not-a-jwt-token"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_QR_INVALID);
  }

  @Test
  @DisplayName("실패: 만료된(TTL 경과) 토큰은 TICKET_QR_EXPIRED를 던진다")
  void verify_fails_when_expired() {
    // given: 이미 만료된 시점(now - 1s)으로 발급된 토큰
    TicketQrPayloadGenerator expiredGenerator = new TicketQrPayloadGenerator(SECRET, -1_000L);
    String expiredToken = expiredGenerator.generate(ticket(1L)).payload();

    // when & then
    assertThatThrownBy(() -> verifier.verify(expiredToken))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_QR_EXPIRED);
  }
}

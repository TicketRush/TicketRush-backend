package com.ticketrush.boundedcontext.ticket.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TicketTokenHasherTest {

  private final TicketTokenHasher hasher = new TicketTokenHasher();

  @Test
  @DisplayName("티켓 토큰은 SHA-256 해시로 변환되어 저장된다")
  void hash_returns_sha256_hex() {
    // when
    String hash = hasher.hash("raw-ticket-token");

    // then
    assertThat(hash).hasSize(64);
    assertThat(hash).matches("^[0-9a-f]{64}$");
    assertThat(hash).doesNotContain("raw-ticket-token");
  }
}

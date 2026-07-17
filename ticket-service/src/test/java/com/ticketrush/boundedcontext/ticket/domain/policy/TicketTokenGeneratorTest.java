package com.ticketrush.boundedcontext.ticket.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TicketTokenGeneratorTest {

  private final TicketTokenGenerator generator = new TicketTokenGenerator();

  @Test
  @DisplayName("티켓 토큰은 URL-safe 랜덤 문자열로 생성된다")
  void generate_returns_url_safe_random_token() {
    // when
    String token = generator.generate();

    // then
    assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
  }

  @Test
  @DisplayName("티켓 토큰은 호출할 때마다 다른 값으로 생성된다")
  void generate_returns_different_tokens() {
    // when
    String first = generator.generate();
    String second = generator.generate();

    // then
    assertThat(first).isNotEqualTo(second);
  }
}

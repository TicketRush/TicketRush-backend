package com.ticketrush.boundedcontext.ticket.domain.policy;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class TicketTokenGenerator {

  private static final int TOKEN_BYTES = 32;
  private final SecureRandom random = new SecureRandom();

  public String generate() {
    byte[] tokenBytes = new byte[TOKEN_BYTES];
    random.nextBytes(tokenBytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
  }
}

package com.ticketrush.boundedcontext.ticket.domain.types;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TicketStatus {
  UNUSED("미사용"),
  USED("사용 완료"),
  CANCELED("취소됨");

  private final String description;
}

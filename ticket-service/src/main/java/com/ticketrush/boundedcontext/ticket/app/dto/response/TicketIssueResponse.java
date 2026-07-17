package com.ticketrush.boundedcontext.ticket.app.dto.response;

public record TicketIssueResponse(boolean issued, String token) {

  public static TicketIssueResponse issued(String token) {
    return new TicketIssueResponse(true, token);
  }

  public static TicketIssueResponse alreadyIssued() {
    return new TicketIssueResponse(false, null);
  }
}

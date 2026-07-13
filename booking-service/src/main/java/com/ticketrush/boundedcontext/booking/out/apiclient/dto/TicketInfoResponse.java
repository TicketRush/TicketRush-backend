package com.ticketrush.boundedcontext.booking.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** ticket-service internal 조회 API 응답의 result 본문(snake_case JSON)을 매핑한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketInfoResponse(
    @JsonProperty("booking_id") Long bookingId,
    @JsonProperty("ticket_status") String ticketStatus) {

  /** ticket-service의 TicketStatus enum 이름과 일치. */
  private static final String USED_STATUS = "USED";

  public boolean isUsed() {
    return USED_STATUS.equals(ticketStatus);
  }
}

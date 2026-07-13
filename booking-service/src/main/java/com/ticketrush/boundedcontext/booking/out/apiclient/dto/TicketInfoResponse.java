package com.ticketrush.boundedcontext.booking.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ticket-service internal 조회 API 응답의 result 본문(snake_case JSON)을 매핑한다.
 *
 * <p>{@code ticketStatus}는 ticket-service {@code TicketStatus}의 이름이 문자열로 건너온 값이다. 무엇을 "입장했다"로 볼지는
 * 정책이므로 여기서 판정하지 않는다({@code TicketRestClient}가 판정한다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketInfoResponse(
    @JsonProperty("booking_id") Long bookingId,
    @JsonProperty("ticket_status") String ticketStatus) {}

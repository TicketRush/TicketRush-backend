package com.ticketrush.boundedcontext.ticket.out.apiclient;

import com.ticketrush.boundedcontext.ticket.out.apiclient.dto.BookingApiResponse;
import com.ticketrush.boundedcontext.ticket.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final RestClient bookingServiceRestClient;

  @Value("${gateway.internal-token}")
  private String internalToken;

  /**
   * booking-service에서 예매 소유자/상태를 동기 조회한다. 예매가 없으면(404) 입장권 미존재와 동일한 TICKET_NOT_FOUND로 통일해 다른 사용자
   * 예매의 존재 여부 노출을 막는다. 그 외 4xx/5xx(인증 불일치·다운스트림 장애 등)와 연결 실패/타임아웃은 모두
   * TICKET_BOOKING_COMMUNICATION_FAILED(503)로 매핑해, 호출 측 사용자에게 500이 새어 나가지 않도록 한다.
   */
  public BookingInfoResponse getBooking(Long bookingId) {
    BookingApiResponse response;
    try {
      response =
          bookingServiceRestClient
              .get()
              .uri("/api/v1/booking/internal/{bookingId}", bookingId)
              .header(INTERNAL_TOKEN_HEADER, internalToken)
              .retrieve()
              .onStatus(
                  status -> status.value() == 404,
                  (request, clientResponse) -> {
                    throw new BusinessException(ErrorStatus.TICKET_NOT_FOUND);
                  })
              .onStatus(
                  status -> status.isError(),
                  (request, clientResponse) -> {
                    log.warn(
                        "[BookingRestClient] booking-service 비정상 응답 status={}, bookingId={}",
                        clientResponse.getStatusCode(),
                        bookingId);
                    throw new BusinessException(ErrorStatus.TICKET_BOOKING_COMMUNICATION_FAILED);
                  })
              .body(BookingApiResponse.class);
    } catch (ResourceAccessException e) {
      log.warn("[BookingRestClient] booking-service 연결 실패/타임아웃 bookingId={}", bookingId, e);
      throw new BusinessException(ErrorStatus.TICKET_BOOKING_COMMUNICATION_FAILED);
    }

    if (response == null || response.result() == null) {
      log.warn(
          "[BookingRestClient] booking-service 200 응답이나 result 본문이 비어 있음 bookingId={}", bookingId);
      throw new BusinessException(ErrorStatus.TICKET_BOOKING_COMMUNICATION_FAILED);
    }
    return response.result();
  }
}

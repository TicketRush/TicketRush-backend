package com.ticketrush.boundedcontext.booking.out.apiclient;

import com.ticketrush.boundedcontext.booking.out.apiclient.dto.TicketApiResponse;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final RestClient ticketServiceRestClient;
  private final CustomSecurityProperties customSecurityProperties;

  /**
   * 예매에 발급된 입장권이 사용(입장 완료)됐는지 ticket-service에 동기 조회한다 (#399).
   *
   * <p>입장권이 없으면(404) 아직 발급되지 않은 예매이므로 입장했을 리 없다 — 예외 대신 {@code false}(미입장)로 해석한다. 그 외 4xx/5xx(인증
   * 불일치·다운스트림 장애 등)와 연결 실패·타임아웃·응답 본문 파싱 실패는 모두 {@code BOOKING_TICKET_COMMUNICATION_FAILED}(503)로
   * 매핑한다 — 하나라도 새어 나가면 사용자에게 원시 500이 노출된다.
   *
   * <p><b>알 수 없으면 막는다</b> — 조회 실패에도 환불을 진행시키면 착석한 좌석이 반환될 수 있으므로, 실패는 좌석을 SOLD로 남기는 안전한 방향(취소 거부)으로
   * 전파한다.
   */
  public boolean isTicketUsed(Long bookingId) {
    TicketApiResponse response;
    try {
      response =
          ticketServiceRestClient
              .get()
              .uri("/api/v1/internal/ticket/bookings/{bookingId}", bookingId)
              .header(INTERNAL_TOKEN_HEADER, customSecurityProperties.getInternalToken())
              .retrieve()
              .onStatus(
                  status -> status.isError() && status.value() != 404,
                  (request, clientResponse) -> {
                    log.warn(
                        "[TicketRestClient] ticket-service 비정상 응답 status={}, bookingId={}",
                        clientResponse.getStatusCode(),
                        bookingId);
                    throw new BusinessException(ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED);
                  })
              .body(TicketApiResponse.class);
    } catch (HttpClientErrorException.NotFound e) {
      log.info("[TicketRestClient] 발급된 입장권이 없어 미입장으로 판단합니다. bookingId={}", bookingId);
      return false;
    } catch (RestClientException e) {
      // 연결 실패·타임아웃(ResourceAccessException), 본문이 JSON이 아닌 경우(UnknownContentTypeException) 등
      // 남은 통신 실패 전반. 하나라도 새어 나가면 사용자에게 원시 500이 노출되므로 여기서 모두 503으로 수렴시킨다.
      log.warn("[TicketRestClient] ticket-service 통신 실패 bookingId={}", bookingId, e);
      throw new BusinessException(ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED);
    }

    if (response == null || response.result() == null) {
      log.warn(
          "[TicketRestClient] ticket-service 200 응답이나 result 본문이 비어 있음 bookingId={}", bookingId);
      throw new BusinessException(ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED);
    }

    return response.result().isUsed();
  }
}

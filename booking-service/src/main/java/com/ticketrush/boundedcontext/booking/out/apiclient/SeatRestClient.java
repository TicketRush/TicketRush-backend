package com.ticketrush.boundedcontext.booking.out.apiclient;

import com.ticketrush.boundedcontext.booking.app.dto.request.SeatSoldConfirmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final RestClient seatServiceRestClient;

  @Value("${gateway.internal-token}")
  private String internalToken;

  /**
   * seat-service에 좌석 SOLD 확정을 요청한다. seat-service가 4xx/5xx로 응답하면 RestClient가 예외를 던지므로, 호출 측(리스너)에서
   * 처리한다.
   */
  public void confirmSold(String bookingNumber, Long seatId) {
    SeatSoldConfirmRequest request = new SeatSoldConfirmRequest(bookingNumber, seatId);

    seatServiceRestClient
        .post()
        .uri("/api/v1/internal/seat/sold")
        .header(INTERNAL_TOKEN_HEADER, internalToken)
        .body(request)
        .retrieve()
        .toBodilessEntity();

    log.info("[좌석 SOLD 확정 요청 성공] bookingNumber={}, seatId={}", bookingNumber, seatId);
  }
}

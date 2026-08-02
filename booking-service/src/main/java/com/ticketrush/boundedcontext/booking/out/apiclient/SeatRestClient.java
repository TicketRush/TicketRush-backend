package com.ticketrush.boundedcontext.booking.out.apiclient;

import com.ticketrush.boundedcontext.booking.app.dto.request.SeatHoldReleaseRequest;
import com.ticketrush.boundedcontext.booking.app.dto.request.SeatSoldConfirmRequest;
import com.ticketrush.global.config.CustomSecurityProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final RestClient seatServiceRestClient;
  private final CustomSecurityProperties customSecurityProperties;

  /**
   * seat-service에 좌석 SOLD 확정을 요청한다. seat-service가 4xx/5xx로 응답하면 RestClient가 예외를 던지므로, 호출 측(리스너)에서
   * 처리한다.
   */
  public void confirmSold(String bookingNumber, Long seatId) {
    SeatSoldConfirmRequest request = new SeatSoldConfirmRequest(bookingNumber, seatId);

    seatServiceRestClient
        .post()
        .uri("/api/v1/internal/seat/sold")
        .header(INTERNAL_TOKEN_HEADER, customSecurityProperties.getInternalToken())
        .body(request)
        .retrieve()
        .toBodilessEntity();

    log.info("[좌석 SOLD 확정 요청 성공] bookingNumber={}, seatId={}", bookingNumber, seatId);
  }

  /**
   * PENDING 예매를 즉시 취소한 뒤 선점 좌석의 반납을 seat-service에 요청한다 (#559).
   *
   * <p><b>실패해도 예외를 밖으로 내지 않는다.</b> 이 호출 시점에 예매는 이미 CANCELED로 커밋됐고 되돌릴 수 없다. 여기서 던지면 사용자에게는 취소가 실패한
   * 것처럼 보이지만 실제로는 취소된, 더 나쁜 상태가 된다. 좌석은 기존 Redis TTL 5분과 60초 스케줄러가 회수하므로 최악의 경우 반납만 늦어진다.
   *
   * <p>seat-service 쪽 API는 멱등하다 — 이미 해제됐거나 다른 예매가 쥔 좌석이면 아무것도 하지 않는다.
   */
  public void releaseHold(String bookingNumber, Long seatId) {
    SeatHoldReleaseRequest request = new SeatHoldReleaseRequest(bookingNumber, seatId);

    try {
      seatServiceRestClient
          .post()
          .uri("/api/v1/internal/seat/release")
          .header(INTERNAL_TOKEN_HEADER, customSecurityProperties.getInternalToken())
          .body(request)
          .retrieve()
          .toBodilessEntity();

      log.info("[좌석 즉시 반납 요청 성공] bookingNumber={}, seatId={}", bookingNumber, seatId);
    } catch (RestClientException e) {
      log.error(
          "[좌석 즉시 반납 요청 실패] 예매는 이미 취소됐습니다. 좌석은 TTL 만료로 회수됩니다(최대 5분). "
              + "bookingNumber={}, seatId={}",
          bookingNumber,
          seatId,
          e);
    }
  }
}

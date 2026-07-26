package com.ticketrush.boundedcontext.ticket.out.apiclient;

import com.ticketrush.boundedcontext.ticket.out.apiclient.dto.BookingApiResponse;
import com.ticketrush.boundedcontext.ticket.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.global.config.BookingCircuitBreakerConfig;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * booking-service 예매 조회 동기 호출 클라이언트.
 *
 * <p><b>서킷 open 시 정책은 fail-fast 503 이다</b>(#496). 검표는 권위 있는 {@code bookingStatus} 확인이 목적이라(#364)
 * 조회가 불가능한 동안 입장을 통과시킬 수 없다 — 환불 진행 중(REFUNDING)인 예매가 그대로 입장하는 것을 막을 수단이 사라지기 때문이다. 서킷 장애의 결과는 가용성
 * 손실(입장 거절)이지 정합성 손실이 아니며, 이 비대칭은 Redis SPOF 에 대한 ADR 0008 의 fail-closed 원칙과 같은 선택이다.
 *
 * <p>서킷 차단으로 떨어진 503 과 실제 호출 실패로 떨어진 503 은 응답에서 구분되지 않는다(둘 다 {@code TICKET_503_001}). 구분이 필요한 측정에서는
 * resilience4j 메트릭의 {@code kind="not_permitted"} 를 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final RestClient bookingServiceRestClient;
  private final CustomSecurityProperties customSecurityProperties;
  private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

  /**
   * booking-service에서 예매 소유자/상태를 동기 조회한다. 호출은 서킷브레이커로 감싸며, 서킷이 열려 있으면 booking 을 치지 않고 즉시
   * TICKET_BOOKING_COMMUNICATION_FAILED(503)로 떨어진다.
   */
  public BookingInfoResponse getBooking(Long bookingId) {
    return circuitBreakerFactory
        .create(BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER)
        .run(() -> call(bookingId), throwable -> fallback(bookingId, throwable));
  }

  /**
   * 예매가 없으면(404) 입장권 미존재와 동일한 TICKET_NOT_FOUND로 통일해 다른 사용자 예매의 존재 여부 노출을 막는다. 그 외 4xx/5xx(인증
   * 불일치·다운스트림 장애 등)와 연결 실패/타임아웃은 모두 TICKET_BOOKING_COMMUNICATION_FAILED(503)로 매핑해, 호출 측 사용자에게 500이
   * 새어 나가지 않도록 한다.
   */
  private BookingInfoResponse call(Long bookingId) {
    BookingApiResponse response;
    try {
      response =
          bookingServiceRestClient
              .get()
              .uri("/api/v1/internal/booking/{bookingId}", bookingId)
              .header(INTERNAL_TOKEN_HEADER, customSecurityProperties.getInternalToken())
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

  /**
   * 서킷 open 뿐 아니라 {@link #call} 안에서 난 모든 예외가 여기로 온다. 이미 사용자용으로 매핑이 끝난 BusinessException 은 그대로 다시
   * 던진다 — 특히 TICKET_NOT_FOUND 를 여기서 503 으로 뭉개면 "없는 예매"가 "장애"로 뒤집힌다.
   */
  private BookingInfoResponse fallback(Long bookingId, Throwable throwable) {
    if (throwable instanceof BusinessException businessException) {
      throw businessException;
    }
    log.warn(
        "[BookingRestClient] booking 조회 차단/실패 bookingId={}, cause={}",
        bookingId,
        throwable.toString());
    throw new BusinessException(ErrorStatus.TICKET_BOOKING_COMMUNICATION_FAILED);
  }
}

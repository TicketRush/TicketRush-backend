package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.out.apiclient.dto.BookingApiResponse;
import com.ticketrush.boundedcontext.payment.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 결제 확정 경로에서 예매 상태를 판정하기 위해 booking-service를 동기 조회하는 클라이언트 (#490).
 *
 * <p>기존 만료 가드는 {@code expired_booking} 테이블에 의존했는데, 그 테이블은 {@code BookingExpiredEvent}가 outbox 릴레이를
 * 두 홉(seat→booking, booking→payment) 타야 채워진다. 대량 만료 구간에서 그 전파가 밀리면(#345 실측 약 13분) 이미 만료된 예매의 PG 승인이
 * 통과해 <b>과금은 됐는데 좌석이 확정되지 않는</b> 상태로 끝났다. 이 클라이언트는 그 판정을 이벤트 도착이 아니라 동기 조회로 바꿔, 되돌릴 수 없는 부작용(과금) 전에
 * 결정적으로 막는 방어선을 만든다.
 *
 * <p><b>조회할 수 없으면 결제를 막는다(fail-closed).</b> 판정 불가한 응답은 모두 {@code
 * PAYMENT_BOOKING_COMMUNICATION_FAILED}(503)로 수렴시킨다. 그 대가로 <b>booking-service 장애가 결제 확정 API의 전면
 * 503으로 번진다</b>는 점을 분명히 해 둔다. 그럼에도 fail-closed인 이유는 셋이다.
 *
 * <ul>
 *   <li>PG 승인은 되돌릴 수 없는 부작용(과금)이고, 그 뒤 좌석이 없을 때 자동으로 되돌리는 보상 경로는 아직 없다(#492).
 *   <li>fail-open으로 통과시켜도 결과가 나아지지 않는다. booking-service가 죽으면 {@code PaymentConfirmedEvent}를 소비해 예매를
 *       확정하고 좌석을 SOLD로 굳히는 주체 자체가 없어, 통과의 귀결이 이 이슈가 고치려는 바로 그 상태다.
 *   <li>ADR 0008이 "가용성 손실이 정합성 손실보다 낫다"를 팀 결정으로 못박았고, 같은 축의 선례가 이미 둘이다 — payment의 {@code
 *       TicketRestClient}, ticket-service의 동명 클라이언트.
 * </ul>
 *
 * <p>서킷브레이커는 아직 없다(#571). ticket-service의 임계값은 그쪽 실측 왕복(#402, 3.20ms)에 맞춰 잡은 값이라 근거 없이 복사하면 오탐
 * open이 나고, fail-closed와 겹치면 그 오탐이 곧 결제 전면 중단이 된다. 그때까지 유일한 완화 수단은 짧은 read-timeout이다({@code
 * service.booking.read-timeout-ms}).
 *
 * <p>그 임계값의 근거를 만들기 위해 이 왕복에 {@link MetricNames#PAYMENT_BOOKING_LOOKUP} Timer를 걸어 두었다(#633). 이
 * 클라이언트가 쓰는 {@code RestClient}는 오토컨피그된 빌더가 아니라 생 {@code RestClient.builder()}로 만들어져 {@code
 * http_client_requests_seconds}가 없으므로, 이 Timer 말고는 구간 지연을 볼 축이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  /** 정상 반환(200 + result 있음). */
  private static final String LOOKUP_OUTCOME_SUCCESS = "success";

  /** booking-service가 의도적으로 낸 404(BOOKING_404_001). 서킷 실패로 세지 않을 갈래다(#571). */
  private static final String LOOKUP_OUTCOME_NOT_FOUND = "not_found";

  /**
   * 성공도 의도된 404도 아닌 나머지 전부. 통신 실패·타임아웃·계약 붕괴처럼 판정 불가(503)로 수렴한 건이 대부분이지만, {@code
   * BusinessException}이 아닌 예외가 새어 원시 500으로 나가는 경로도 이 갈래로 센다 — #571 관점에서 둘 다 "정상 응답을 받지 못한 호출"로 같기
   * 때문이다.
   */
  private static final String LOOKUP_OUTCOME_FAILED = "failed";

  private final RestClient bookingServiceRestClient;
  private final CustomSecurityProperties customSecurityProperties;
  private final MeterRegistry meterRegistry;

  /**
   * 예매 정보를 booking-service에 동기 조회한다 (#490).
   *
   * <p>상태 문자열을 그대로 담은 DTO를 돌려줄 뿐 <b>결제 가능 여부를 판정하지 않는다</b>. 어떤 상태를 통과시킬지는 결제 정책이라 {@code
   * PaymentConfirmUseCase}가 허용 목록으로 판정한다.
   *
   * <p>정상 반환하는 경로는 200 + {@code result}가 있는 경우 하나뿐이다. 나머지는 전부 예외로 끊는다.
   *
   * <p>호출 전체를 {@link MetricNames#PAYMENT_BOOKING_LOOKUP} Timer로 감싼다(#633). 재는 구간을 HTTP 왕복이 아니라 <b>이
   * 메서드 진입~반환</b>으로 잡은 것은 #571이 서킷으로 감쌀 구간과 같은 범위를 재기 위해서다 — 서킷은 호출 전체를 보고 판정하므로, 예외 변환까지 포함한 이 구간이
   * 임계값이 실제로 걸리는 지점이다.
   *
   * @throws BusinessException {@code BOOKING_NOT_FOUND}(404) — booking-service가 {@code
   *     BOOKING_404_001}로 응답한 경우. 없는 예매로 결제를 진행시킬 수는 없으므로 이 역시 결제를 막는다.
   * @throws BusinessException {@code PAYMENT_BOOKING_COMMUNICATION_FAILED}(503) — 그 밖의 모든 판정 불가 응답
   */
  public BookingInfoResponse getBooking(Long bookingId) {
    Timer.Sample sample = Timer.start(meterRegistry);
    // 초기값을 failed로 둔다. BusinessException이 아닌 예외가 새어 나가도 성공으로 집계되지 않아야 한다.
    String outcome = LOOKUP_OUTCOME_FAILED;
    try {
      BookingInfoResponse result = doGetBooking(bookingId);
      outcome = LOOKUP_OUTCOME_SUCCESS;
      return result;
    } catch (BusinessException e) {
      outcome =
          ErrorStatus.BOOKING_NOT_FOUND == e.getErrorStatus()
              ? LOOKUP_OUTCOME_NOT_FOUND
              : LOOKUP_OUTCOME_FAILED;
      throw e;
    } finally {
      sample.stop(
          Timer.builder(MetricNames.PAYMENT_BOOKING_LOOKUP)
              .tag(MetricNames.TAG_OUTCOME, outcome)
              .register(meterRegistry));
    }
  }

  private BookingInfoResponse doGetBooking(Long bookingId) {
    BookingApiResponse response;
    try {
      response =
          bookingServiceRestClient
              .get()
              .uri("/api/v1/internal/booking/{bookingId}", bookingId)
              .header(INTERNAL_TOKEN_HEADER, customSecurityProperties.getInternalToken())
              .retrieve()
              .onStatus(
                  status -> status.isError() && status.value() != 404,
                  (request, clientResponse) -> {
                    log.warn(
                        "[BookingRestClient] booking-service 비정상 응답 status={}, bookingId={}",
                        clientResponse.getStatusCode(),
                        bookingId);
                    throw new BusinessException(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);
                  })
              .body(BookingApiResponse.class);
    } catch (HttpClientErrorException.NotFound e) {
      throw notFoundToBusinessException(e, bookingId);
    } catch (RestClientException e) {
      // 연결 실패·타임아웃(ResourceAccessException), 본문이 JSON이 아닌 경우(UnknownContentTypeException) 등
      // 남은 통신 실패 전반. 하나라도 새어 나가면 사용자에게 원시 500이 노출되므로 여기서 모두 503으로 수렴시킨다.
      log.warn("[BookingRestClient] booking-service 통신 실패 bookingId={}", bookingId, e);
      throw new BusinessException(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);
    }

    if (response == null || response.result() == null) {
      log.warn(
          "[BookingRestClient] booking-service 200 응답이나 result 본문이 비어 있음 bookingId={}", bookingId);
      throw new BusinessException(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);
    }

    return response.result();
  }

  /**
   * booking-service가 의도적으로 낸 404(예매 없음)와 그 밖의 404를 가른다.
   *
   * <p>어느 쪽이든 결제는 막히므로 정합성은 같지만, 응답 코드를 갈라 두면 <b>설정 오류가 조용히 묻히지 않는다</b>. {@code
   * BOOKING_SERVICE_URL}을 게이트웨이 주소로 잘못 넣으면 내부 경로가 라우팅되지 않아(ADR 0002) 모든 요청이 404가 되는데, 이를 도메인 404와
   * 같이 취급하면 전건이 "예매를 찾을 수 없습니다"로 거절되면서 원인이 드러나지 않는다.
   */
  private BusinessException notFoundToBusinessException(
      HttpClientErrorException.NotFound e, Long bookingId) {
    String code = errorCodeOf(e);

    if (ErrorStatus.BOOKING_NOT_FOUND.getCode().equals(code)) {
      log.warn("[BookingRestClient] 존재하지 않는 예매입니다. bookingId={}", bookingId);
      return new BusinessException(ErrorStatus.BOOKING_NOT_FOUND);
    }

    log.warn(
        "[BookingRestClient] 예매 없음(BOOKING_404_001)이 아닌 404입니다. 경로·배포 설정을 확인하세요. "
            + "bookingId={}, code={}",
        bookingId,
        code);
    return new BusinessException(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);
  }

  /** 에러 응답 본문에서 code를 꺼낸다. 본문이 없거나 파싱되지 않으면 null(= 의도된 404가 아님)로 본다. */
  private String errorCodeOf(HttpClientErrorException.NotFound e) {
    try {
      BookingApiResponse body = e.getResponseBodyAs(BookingApiResponse.class);
      return (body != null) ? body.code() : null;
    } catch (RestClientException parseFailure) {
      return null;
    }
  }
}

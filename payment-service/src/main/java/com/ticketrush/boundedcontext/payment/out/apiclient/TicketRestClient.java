package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.out.apiclient.dto.TicketApiResponse;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 결제 취소 경로에서 입장권 사용 여부를 판정하는 클라이언트 (#416).
 *
 * <p>결제 취소 API는 booking을 통과하지 않아, #399가 booking 경로(자가 취소·관리자 재환불)에 세운 "입장 완료 예매 환불 차단" 정책을 그대로
 * 우회한다. booking의 동명 클라이언트와 같은 계약(엔드포인트·헤더·fail-closed 판정)으로 ticket-service를 동기 조회해, payment 취소 경로에도
 * 동일한 규율을 적용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  /** ticket-service TicketStatus의 이름. 문자열로 건너오므로 컴파일러가 지켜주지 못한다. */
  private static final String STATUS_USED = "USED";

  private static final String STATUS_UNUSED = "UNUSED";
  private static final String STATUS_CANCELED = "CANCELED";

  private final RestClient ticketServiceRestClient;
  private final CustomSecurityProperties customSecurityProperties;

  /**
   * 예매에 발급된 입장권이 사용(입장 완료)됐는지 ticket-service에 동기 조회한다 (#416).
   *
   * <p><b>알 수 없으면 막는다.</b> 조회 실패에도 환불을 진행시키면 착석한 좌석이 반환될 수 있으므로, 판정할 수 없는 응답은 모두 {@code
   * PAYMENT_TICKET_COMMUNICATION_FAILED}(503)로 수렴시켜 좌석을 SOLD로 남기는 안전한 방향(취소 거부)으로 전파한다. 이 클래스에서
   * {@code false}(환불 허용)를 반환하는 분기는 아래 둘뿐이며, 나머지는 전부 막는다.
   *
   * <ul>
   *   <li><b>입장권 미발급</b> — ticket-service가 {@code TICKET_404_001}로 응답한 404. 티켓 발급은 결제 완료 이벤트 이후라
   *       CONFIRMED이면서 티켓 행이 아직 없는 상태가 실재하고(발급 이벤트 DLT 등), 그 예매는 입장이 불가능하므로 취소를 막아선 안 된다. <b>그 밖의
   *       404는 미입장으로 해석하지 않는다</b> — 경로 오설정·라우팅 오류로 나는 404까지 통과시키면 정책이 조용히 무력화된다.
   *   <li><b>UNUSED / CANCELED</b> — 입장하지 않은 입장권.
   * </ul>
   *
   * <p>알 수 없는 상태 문자열도 막는다. ticket-service가 {@code TicketStatus}에 값을 추가하거나 이름을 바꿔도 payment는 컴파일 에러
   * 없이 이 코드를 통과하므로, 모르는 값을 "미입장"으로 낙관하면 정책이 소리 없이 뚫린다.
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
                    throw new BusinessException(ErrorStatus.PAYMENT_TICKET_COMMUNICATION_FAILED);
                  })
              .body(TicketApiResponse.class);
    } catch (HttpClientErrorException.NotFound e) {
      return handleNotFound(e, bookingId);
    } catch (RestClientException e) {
      // 연결 실패·타임아웃(ResourceAccessException), 본문이 JSON이 아닌 경우(UnknownContentTypeException) 등
      // 남은 통신 실패 전반. 하나라도 새어 나가면 사용자에게 원시 500이 노출되므로 여기서 모두 503으로 수렴시킨다.
      log.warn("[TicketRestClient] ticket-service 통신 실패 bookingId={}", bookingId, e);
      throw new BusinessException(ErrorStatus.PAYMENT_TICKET_COMMUNICATION_FAILED);
    }

    if (response == null || response.result() == null) {
      log.warn(
          "[TicketRestClient] ticket-service 200 응답이나 result 본문이 비어 있음 bookingId={}", bookingId);
      throw new BusinessException(ErrorStatus.PAYMENT_TICKET_COMMUNICATION_FAILED);
    }

    return isUsed(response.result().ticketStatus(), bookingId);
  }

  /** ticket-service가 의도적으로 낸 404(입장권 미발급)만 미입장으로 해석한다. */
  private boolean handleNotFound(HttpClientErrorException.NotFound e, Long bookingId) {
    String code = errorCodeOf(e);

    if (ErrorStatus.TICKET_NOT_FOUND.getCode().equals(code)) {
      log.info("[TicketRestClient] 발급된 입장권이 없어 미입장으로 판단합니다. bookingId={}", bookingId);
      return false;
    }

    // 핸들러가 없는 경로(오타·매핑 변경)나 라우팅 오설정도 404다. 이를 미입장으로 통과시키면 정책이 사일런트로 무력화된다.
    log.warn(
        "[TicketRestClient] 입장권 미발급(TICKET_404_001)이 아닌 404입니다. 경로·배포 설정을 확인하세요. "
            + "bookingId={}, code={}",
        bookingId,
        code);
    throw new BusinessException(ErrorStatus.PAYMENT_TICKET_COMMUNICATION_FAILED);
  }

  /** 에러 응답 본문에서 code를 꺼낸다. 본문이 없거나 파싱되지 않으면 null(= 의도된 404가 아님)로 본다. */
  private String errorCodeOf(HttpClientErrorException.NotFound e) {
    try {
      TicketApiResponse body = e.getResponseBodyAs(TicketApiResponse.class);
      return (body != null) ? body.code() : null;
    } catch (RestClientException parseFailure) {
      return null;
    }
  }

  /** 알 수 없는 상태 문자열은 통과시키지 않는다(fail-closed). */
  private boolean isUsed(String ticketStatus, Long bookingId) {
    if (STATUS_USED.equals(ticketStatus)) {
      return true;
    }
    if (STATUS_UNUSED.equals(ticketStatus) || STATUS_CANCELED.equals(ticketStatus)) {
      return false;
    }

    log.warn(
        "[TicketRestClient] 알 수 없는 입장권 상태라 판정할 수 없습니다. bookingId={}, ticketStatus={}",
        bookingId,
        ticketStatus);
    throw new BusinessException(ErrorStatus.PAYMENT_TICKET_COMMUNICATION_FAILED);
  }
}

package com.ticketrush.boundedcontext.performance.out.apiclient;

import com.ticketrush.boundedcontext.performance.out.apiclient.dto.BookingStatsApiResponse;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.BookingStatsInfo;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.util.ServiceUrlValidator;
import java.time.LocalDate;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 관리자 대시보드의 매출·판매 집계 조회 클라이언트 (#563).
 *
 * <p>booking-service의 <b>내부</b> API를 호출하므로 {@code X-Internal-Token}을 싣는다. 관리자 예매 통계
 * API(`/api/v1/booking/admin/**`)를 부르지 않는 이유는 그쪽이 {@code hasRole("ADMIN")} 경계이고, ADMIN 권한은 게이트웨이가
 * 사용자 헤더로만 부여하기 때문이다 — 서비스가 그 헤더를 스스로 만들면 게이트웨이 신뢰 경계를 사칭하는 셈이 된다. 값 자체는 같다(내부 API가 같은 유스케이스를
 * 재사용한다).
 *
 * <p><b>실패해도 예외를 밖으로 내지 않는다.</b> 여기서 던지면 booking-service 장애가 대시보드 전체를 죽여, 공연 목록·상태 같은 performance
 * 자체 정보까지 함께 보이지 않게 된다. 매출 관련 필드만 비운 채 응답한다(booking 관리자 목록이 보강 실패를 다루는 방식과 같다).
 */
@Slf4j
@Component
public class BookingRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final RestClient bookingServiceRestClient;
  private final CustomSecurityProperties securityProperties;
  private final boolean configured;

  public BookingRestClient(
      RestClient bookingServiceRestClient,
      CustomSecurityProperties securityProperties,
      @Value("${service.booking.url:}") String bookingServiceUrl) {
    this.bookingServiceRestClient = bookingServiceRestClient;
    this.securityProperties = securityProperties;
    this.configured = ServiceUrlValidator.isUsable(bookingServiceUrl);
  }

  /**
   * 요약·공연별·일별 집계를 한 번에 가져온다. 모든 실패는 {@code Optional.empty()}로 수렴한다.
   *
   * <p><b>쓸 수 없는 URL을 호출 전에 막는다.</b> 비어 있는 경우뿐 아니라 스킴이 빠진 경우({@code booking-service:8084})까지 막아야 한다
   * — 둘 다 요청 시점에 {@code IllegalArgumentException}이 되는데, 그건 {@code RestClientException}이 아니라서 아래
   * catch를 그대로 뚫고 <b>대시보드 전체가 500</b>이 된다. fail-open이 성립하려면 이 검사가 선행되어야 한다.
   *
   * @param from 일별 매출 시작일(포함) — 요약과 공연별 집계는 이 기간의 영향을 받지 않는다
   * @param to 일별 매출 종료일(포함)
   */
  public Optional<BookingStatsInfo> getStats(LocalDate from, LocalDate to) {
    if (!configured) {
      return Optional.empty();
    }

    try {
      BookingStatsApiResponse response =
          bookingServiceRestClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/api/v1/internal/booking/stats")
                          .queryParam("from", from)
                          .queryParam("to", to)
                          .build())
              .header(INTERNAL_TOKEN_HEADER, securityProperties.getInternalToken())
              .retrieve()
              .body(BookingStatsApiResponse.class);

      return Optional.ofNullable(response).map(BookingStatsApiResponse::result);
    } catch (RestClientException e) {
      log.warn(
          "[BookingRestClient] 예매 집계 조회 실패, 매출 관련 필드는 null로 응답합니다. from={}, to={}", from, to, e);
      return Optional.empty();
    }
  }
}

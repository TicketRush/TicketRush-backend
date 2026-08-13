package com.ticketrush.boundedcontext.performance.out.apiclient;

import com.ticketrush.boundedcontext.performance.out.apiclient.dto.SeatCountsApiResponse;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.SeatCountsInfo;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.util.ServiceUrlValidator;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * 관리자 대시보드의 좌석 점유율 조회 클라이언트 (#563).
 *
 * <p>공연별 단건 조회(`/api/v1/seat/{id}/seat-counts`)를 공연 수만큼 반복하지 않고 <b>일괄 내부 API를 한 번</b> 부른다. 평균 점유율은
 * 전체 공연이 모수라, 순차 호출은 공연이 늘수록 선형으로 느려지고 중간 한 건의 실패가 평균 전체를 왜곡한다.
 *
 * <p><b>실패해도 예외를 밖으로 내지 않는다.</b> 좌석 서비스 장애 시 점유율 관련 필드만 비고 대시보드의 나머지는 그대로 보인다.
 */
@Slf4j
@Component
public class SeatRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
  private static final String SEAT_COUNTS_PATH = "/api/v1/internal/seat/seat-counts";

  private final RestClient seatServiceRestClient;
  private final CustomSecurityProperties securityProperties;
  private final boolean configured;

  public SeatRestClient(
      RestClient seatServiceRestClient,
      CustomSecurityProperties securityProperties,
      @Value("${service.seat.url:}") String seatServiceUrl) {
    this.seatServiceRestClient = seatServiceRestClient;
    this.securityProperties = securityProperties;
    this.configured = ServiceUrlValidator.isUsable(seatServiceUrl);
  }

  /**
   * 전 공연 좌석 수를 공연 ID로 색인해 반환한다. 실패하면 <b>빈 맵</b>이다.
   *
   * <p>빈 맵과 "좌석이 0석"은 호출자에게 같은 모양이 아니다 — 맵에 키가 없다는 것은 값을 <b>모른다</b>는 뜻이며, 호출자는 그 공연의 점유율을 0%가 아니라
   * null로 내려야 한다. 좌석 생성은 공연 등록 이벤트를 받아 비동기로 일어나므로, 방금 등록된 공연은 정상 응답에서도 키가 없다.
   *
   * <p>{@code toMap()}을 쓰지 않는다. 좌석 쪽 응답에 같은 공연 ID가 두 번 들어오면 {@code IllegalStateException}이 나는데, 그건
   * {@code RestClientException}이 아니라 아래 catch를 뚫고 500이 된다 — 부분 응답으로 살아남아야 할 경로가 전체 실패로 뒤집힌다.
   */
  public Map<Long, SeatCountsInfo> getSeatCounts() {
    if (!configured) {
      return Map.of();
    }

    return fetch(uriBuilder -> uriBuilder.path(SEAT_COUNTS_PATH).build(), "전 공연");
  }

  /**
   * 지정한 공연들의 좌석 수만 가져온다 (#590 관리자 공연 목록). 반환 계약은 전건 버전과 같다.
   *
   * <p><b>빈 목록이면 호출하지 않는다.</b> 빈 값을 그대로 보내면 수신측 {@code @Size(min=1)}에 걸려 400이 되고, 그 400은 fail-open이
   * 흡수해 "공연 0건 페이지"가 "좌석 서비스 장애"로 로그에 남는다. 중복 제거도 여기서 닫는다.
   */
  public Map<Long, SeatCountsInfo> getSeatCounts(List<Long> performanceIds) {
    if (!configured || performanceIds == null) {
      return Map.of();
    }

    List<Long> distinctIds = performanceIds.stream().filter(Objects::nonNull).distinct().toList();
    if (distinctIds.isEmpty()) {
      return Map.of();
    }

    return fetch(
        uriBuilder ->
            uriBuilder
                .path(SEAT_COUNTS_PATH)
                .queryParam(
                    "performanceIds",
                    distinctIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .build(),
        "공연 " + distinctIds.size() + "건");
  }

  private Map<Long, SeatCountsInfo> fetch(Function<UriBuilder, URI> uriFunction, String scope) {
    try {
      SeatCountsApiResponse response =
          seatServiceRestClient
              .get()
              .uri(uriFunction)
              .header(INTERNAL_TOKEN_HEADER, securityProperties.getInternalToken())
              .retrieve()
              .body(SeatCountsApiResponse.class);

      List<SeatCountsInfo> rows = (response == null) ? null : response.result();
      if (rows == null) {
        log.warn("[SeatRestClient] 좌석 수 응답 본문이 비어 있어 점유율 필드를 null로 응답합니다. scope={}", scope);
        return Map.of();
      }

      Map<Long, SeatCountsInfo> result = new HashMap<>();
      for (SeatCountsInfo row : rows) {
        if (row != null && row.performanceId() != null) {
          result.put(row.performanceId(), row);
        }
      }
      return result;
    } catch (RestClientException e) {
      log.warn("[SeatRestClient] 좌석 수 조회 실패, 점유율 관련 필드는 null로 응답합니다. scope={}", scope, e);
      return Map.of();
    }
  }
}

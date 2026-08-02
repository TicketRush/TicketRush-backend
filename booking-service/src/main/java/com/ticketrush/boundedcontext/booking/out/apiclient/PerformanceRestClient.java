package com.ticketrush.boundedcontext.booking.out.apiclient;

import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceApiResponse;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 예매 조회 응답 보강용 공연 상세 조회 클라이언트. performance-service의 <b>공개</b> API를 호출하므로 내부 토큰이 없다.
 *
 * <p><b>실패해도 예외를 밖으로 내지 않는다(#560 부분 응답 정책).</b> 여기서 던지면 performance-service 장애가 예매 조회 전체를 죽인다.
 * booking 코어 필드는 이미 DB에서 확정됐으므로, 공연 필드만 비운 채 응답하고 프론트가 performanceId로 재조회하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceRestClient {

  private final RestClient performanceServiceRestClient;

  /** 단건 조회. 4xx·5xx·타임아웃·본문 결손 등 모든 실패는 {@code Optional.empty()}로 수렴한다. */
  public Optional<PerformanceInfoResponse> getPerformance(Long performanceId) {
    try {
      return Optional.ofNullable(fetch(performanceId));
    } catch (RestClientException e) {
      log.warn(
          "[PerformanceRestClient] 공연 조회 실패, 공연 필드는 null로 응답합니다. performanceId={}",
          performanceId,
          e);
      return Optional.empty();
    }
  }

  /**
   * 벌크 조회. 공개 API에 벌크 엔드포인트가 없어 순차 호출한다(호출 측이 distinct 보장, 페이지 기본 10건).
   *
   * <p>4xx(삭제된 공연 등)는 해당 건만 비우고 계속 가지만, 그 외 실패(5xx·타임아웃)는 서비스 장애로 보고 잔여 호출을 중단한다 — 장애 중 남은 건들이
   * 타임아웃을 반복하며 톰캣 스레드를 n×2초씩 붙잡는 것을 막는다. 실패한 공연은 맵에서 빠진다.
   */
  public Map<Long, PerformanceInfoResponse> getPerformances(Collection<Long> performanceIds) {
    Map<Long, PerformanceInfoResponse> result = new HashMap<>();
    for (Long performanceId : performanceIds) {
      try {
        PerformanceInfoResponse info = fetch(performanceId);
        if (info != null) {
          result.put(performanceId, info);
        }
      } catch (HttpClientErrorException e) {
        log.warn(
            "[PerformanceRestClient] 공연 조회 실패({}), 해당 건만 비우고 계속합니다. performanceId={}",
            e.getStatusCode(),
            performanceId);
      } catch (RestClientException e) {
        log.warn(
            "[PerformanceRestClient] performance-service 통신 실패, 잔여 공연 조회를 중단합니다. performanceId={}",
            performanceId,
            e);
        break;
      }
    }
    return result;
  }

  private PerformanceInfoResponse fetch(Long performanceId) {
    PerformanceApiResponse response =
        performanceServiceRestClient
            .get()
            .uri("/api/v1/performance/{id}", performanceId)
            .retrieve()
            .body(PerformanceApiResponse.class);
    return (response == null) ? null : response.result();
  }
}

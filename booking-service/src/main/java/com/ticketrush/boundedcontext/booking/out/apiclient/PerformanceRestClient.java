package com.ticketrush.boundedcontext.booking.out.apiclient;

import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceApiResponse;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class PerformanceRestClient {

  private final RestClient performanceServiceRestClient;
  private final long bulkBudgetMs;

  public PerformanceRestClient(
      RestClient performanceServiceRestClient,
      @Value("${service.performance.bulk-budget-ms:3000}") long bulkBudgetMs) {
    this.performanceServiceRestClient = performanceServiceRestClient;
    this.bulkBudgetMs = bulkBudgetMs;
  }

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
   * 벌크 조회. 공개 API에 벌크 엔드포인트가 없어 순차 호출한다(호출 측이 distinct 보장).
   *
   * <p>중단 조건이 둘이다. <b>통신 실패(5xx·타임아웃)</b>는 서비스 장애로 보고 잔여 호출을 접는다 — 장애 중 남은 건들이 타임아웃을 반복하며 톰캣 스레드를
   * 건당 예산만큼 더 붙잡는 것을 막는다. 4xx(삭제된 공연 등)는 해당 건만 비우고 계속 간다.
   *
   * <p><b>벽시계 예산</b>은 그것만으로 부족해서 둔다. performance-service가 살아 있으면서 느리기만 하면(건당 read 예산 미만) 예외가 나지 않아
   * 위 중단이 돌지 않고, 페이지 상한 50건이 그대로 누적돼 요청 하나가 스레드를 수십 초 붙잡는다. 예산을 넘기면 잔여 공연 필드를 비운 채 응답한다 — 부분 응답 정책이
   * 이미 허용하는 결과다.
   *
   * <p>실패했거나 예산에 걸려 건너뛴 공연은 맵에서 빠진다.
   */
  public Map<Long, PerformanceInfoResponse> getPerformances(Collection<Long> performanceIds) {
    Map<Long, PerformanceInfoResponse> result = new HashMap<>();
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(bulkBudgetMs);

    for (Long performanceId : performanceIds) {
      if (System.nanoTime() >= deadline) {
        log.warn(
            "[PerformanceRestClient] 보강 예산({}ms) 초과, 잔여 공연 필드를 비웁니다. 조회 성공 {}/{}건",
            bulkBudgetMs,
            result.size(),
            performanceIds.size());
        break;
      }

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

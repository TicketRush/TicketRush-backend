package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListSlice;
import com.ticketrush.boundedcontext.performance.app.mapper.PerformanceMapper;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.apiclient.SeatRestClient;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.SeatCountsInfo;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.constants.CacheConstants;
import com.ticketrush.global.dto.request.CursorPageRequest;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PerformanceGetListUseCase {

  private final PerformanceRepository performanceRepository;
  private final PerformanceMapper performanceMapper;
  private final SeatRestClient seatRestClient;

  /**
   * 캐싱 대상은 메인 화면 트래픽이 집중되는 <b>무필터 + 첫 페이지</b> 조합만으로 제한한다 — minPrice/maxPrice/cursorId가 자유값이라 전 조합
   * 캐싱은 키 카디널리티가 무한하기 때문(키는 size별 최대 {@code MAX_PAGE_SIZE}개).
   *
   * <p><b>좌석 수는 캐시 안에서 합성한다</b> (#176). 따라서 좌석 수도 목록과 같은 수명을 갖는다 — 최대 TTL만큼 지난 값일 수 있고, 좌석 상태가 바뀌어도
   * 캐시는 깨지지 않는다(evict는 공연 변경에만 걸려 있다). 좌석의 실시간성은 상세 화면 SSE가 담당하며, 목록에서까지 최신을 보장하려 들면 오픈런 순간 메인 화면
   * 트래픽이 그대로 좌석 서비스의 쓰기 핫패스로 흐른다. 반대로 필터·커서가 걸린 요청은 애초에 캐시를 타지 않으므로 매 요청 좌석을 조회한다.
   *
   * <p><b>TTL이 만료되는 순간의 동시 요청은 각자 좌석 서비스를 부른다</b> — {@code sync}를 걸지 않아 캐시 미스가 직렬화되지 않는다. #177까지는 그
   * 스탬피드가 DB SELECT 한 번씩이었지만, 이제 좌석 내부 API 호출이 함께 증폭되고 그 끝은 예매가 쓰는 좌석 테이블 집계다. 위 문단이 경계한 현상이 30초마다
   * 축소판으로 재현되는 셈이라 지금은 감수하되, 미스 순간의 동시성 제어는 공개 경로 전용 타임아웃 분리와 함께 후속으로 둔다.
   *
   * <p><b>트랜잭션을 열지 않는다.</b> 좌석 서비스 응답을 기다리는 동안 DB 커넥션을 붙잡지 않기 위해서다 — 캐시 미스가 몰리는 순간(TTL 만료·Redis 장애로
   * 인한 fail-open·필터 요청)이 곧 커넥션이 말라붙는 순간이 된다. 조회가 단일 SELECT 한 번이고 매핑 대상이 전부 basic 컬럼이라 지연 로딩이 없어
   * 트랜잭션으로 얻을 것도 없다. 관리자 목록·대시보드가 같은 이유로 이미 무트랜잭션이다. 이 유스케이스에 쓰기나 다중 쿼리를 더한다면 그때 경계를 다시 판단해야 한다.
   */
  @Cacheable(
      cacheNames = CacheConstants.PERFORMANCE_LIST_CACHE,
      key = "'size=' + #pageRequest.size()",
      condition =
          "#genre == null && #minPrice == null && #maxPrice == null && #status == null"
              + " && #pageRequest.cursorId() == null")
  public PerformanceListSlice execute(
      Genre genre,
      Long minPrice,
      Long maxPrice,
      PerformanceStatus status,
      CursorPageRequest pageRequest) {

    validatePriceRange(minPrice, maxPrice);

    Slice<PerformanceListResponse> performances =
        performanceRepository
            .findByFilters(
                genre, minPrice, maxPrice, status, pageRequest.cursorId(), pageRequest.size())
            .map(performanceMapper::toListResponse);

    // 페이지당 한 번만 부른다. 조회 자체는 size+1건이지만 초과분은 Slice가 이미 잘라낸 뒤라
    // content 기준으로 ID를 뽑으면 다음 페이지 몫까지 함께 묻는 일이 없다.
    Map<Long, SeatCountsInfo> seatCounts =
        seatRestClient.getSeatCounts(
            performances.getContent().stream()
                .map(PerformanceListResponse::performanceId)
                .toList());

    return PerformanceListSlice.from(
        performances.map(performance -> applySeatCounts(performance, seatCounts)));
  }

  /**
   * 좌석 수를 아는 공연에만 값을 얹는다. 맵에 키가 없다는 것은 0석이 아니라 <b>모른다</b>는 뜻이므로(좌석 서비스 조회 실패 또는 좌석 미생성) 필드를 비운 채
   * 둔다.
   *
   * <p>전체 좌석이 0인 경우도 같이 비운다. 좌석 집계가 GROUP BY라 좌석이 한 행도 없는 공연은 애초에 맵에 없어 사실상 도달하지 않지만, 도달하더라도 분모 0인
   * 게이지를 클라이언트에 넘기지 않는다.
   */
  private PerformanceListResponse applySeatCounts(
      PerformanceListResponse performance, Map<Long, SeatCountsInfo> seatCounts) {

    SeatCountsInfo counts = seatCounts.get(performance.performanceId());
    if (counts == null || counts.totalCount() == 0) {
      return performance;
    }

    return performance.withSeatCounts(
        counts.totalCount(), counts.totalCount() - counts.soldCount());
  }

  private void validatePriceRange(Long minPrice, Long maxPrice) {
    if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_INVALID_PRICE_RANGE);
    }
  }
}

package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceAdminSummaryResponse;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.performance.out.apiclient.SeatRestClient;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.BookingStatsInfo;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.SeatCountsInfo;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * 관리자 공연 목록 조회 (#563). 공개 목록과 달리 판매·점유율·매출을 함께 내린다.
 *
 * <p><b>공개 목록 유스케이스를 재사용하지 않는다.</b> 그쪽은 무필터 첫 페이지를 Redis에 캐싱하는데, 캐시 키가 페이지 크기 하나뿐이라 관리자 응답과 공개 응답이
 * 같은 키를 공유하게 된다. 캐시 값의 직렬화기도 공개 목록 DTO 타입에 고정돼 있어 집계 필드가 붙은 응답을 같은 캐시에 넣으면 역직렬화가 깨진다. 관리 화면에 stale
 * 매출이 보이는 것도 그 자체로 결함이라, 이 경로는 캐싱하지 않는다.
 *
 * <p>정렬은 최신 등록순(ID 내림차순) 고정이다. 정렬 파라미터를 열면 비인덱스 컬럼 정렬을 클라이언트가 지정할 수 있게 되는데, 그 문제는 별도 이슈(#475)에서 결제
 * 목록을 대상으로 다루는 중이라 여기서 같은 구멍을 새로 열지 않는다.
 *
 * <p>트랜잭션을 열지 않는 이유는 대시보드 유스케이스와 같다 — 원격 호출 대기 중에 DB 커넥션을 붙잡지 않기 위해서다.
 *
 * <p><b>원격 집계에 현재 페이지의 공연 ID만 실어 보낸다(#590).</b> 도입 당시(#563)에는 예매·좌석 어느 쪽도 공연 ID로 좁히는 축이 없어 페이지를 넘길
 * 때마다 두 테이블에 전건 GROUP BY가 반복됐다 — 그 둘은 오픈런 트래픽을 직접 받는 쓰기 핫패스다. 두 내부 API에 옵션 필터를 열어 해소했다.
 *
 * <p>남은 비용은 페이지당 원격 호출 2회이고, 각 호출이 넘기는 ID는 페이지 크기(상한 50)만큼이다. 예매 쪽은 필터를 주면 요약·일별 집계를 아예 건너뛰므로 쿼리도
 * 3개에서 1개로 준다. 좌석 쪽은 예매 경로용 인덱스의 선두 컬럼이 {@code performance_id}라 읽는 행 자체가 줄고, 예매 쪽은 그런 인덱스가 없어 스캔량은
 * 그대로이고 그룹·정렬 대상만 준다. 두 테이블 모두 이 조회를 위해 인덱스를 새로 얹지는 않았다 — 실측과 근거는 각 리포지토리 주석에 있다.
 *
 * <p>대시보드({@code PerformanceGetAdminDashboardUseCase})는 전 공연이 모수라 이 필터를 쓰지 않는다. 그래서 필터는
 * <b>옵션</b>이고, 주지 않으면 기존 전량 동작이 그대로 유지된다.
 */
@Service
@RequiredArgsConstructor
public class PerformanceGetAdminListUseCase {

  private static final int OCCUPANCY_SCALE = 4;

  private final PerformanceRepository performanceRepository;
  private final BookingRestClient bookingRestClient;
  private final SeatRestClient seatRestClient;

  public Page<PerformanceAdminSummaryResponse> execute(OffsetPageRequest pageRequest) {
    Page<Performance> page =
        performanceRepository.findAll(
            PageRequest.of(
                pageRequest.page(), pageRequest.size(), Sort.by(Sort.Direction.DESC, "id")));

    List<Long> performanceIds = page.getContent().stream().map(Performance::getId).toList();

    Map<Long, Long> revenueByPerformance = fetchRevenueByPerformance(performanceIds);
    Map<Long, SeatCountsInfo> seatCounts = seatRestClient.getSeatCounts(performanceIds);

    return page.map(performance -> toSummary(performance, revenueByPerformance, seatCounts));
  }

  /**
   * 현재 페이지 공연들의 매출을 가져온다.
   *
   * <p>기간을 넘기지 않는 것은 목록이 일별 매출을 쓰지 않기 때문이다. 공연 ID로 좁힌 요청에는 예매 서비스가 공연별 집계만 계산해 내려주므로, 쓰지도 않을 요약·일별
   * 쿼리가 아예 돌지 않는다. 공연별 매출은 어느 기간을 넘기든 전체 기간 값이다.
   *
   * <p><b>조회 실패는 {@code null}, 성공은 (비어 있더라도) 맵으로 갈린다.</b> 빈 맵으로 뭉개면 "확정된 예매가 없어 매출 0원"과 "매출을 못
   * 읽었다"가 응답에서 같은 모양이 된다 — 관리자가 구분할 수 없는 두 상태다. 공연이 0건인 페이지는 원격 호출 없이 {@code null}이 되지만, 그릴 행이 없어
   * 관측되지 않는다.
   *
   * @return 공연 ID별 매출. 예매 서비스 조회에 실패하면 {@code null}
   */
  private Map<Long, Long> fetchRevenueByPerformance(List<Long> performanceIds) {
    Optional<BookingStatsInfo> stats = bookingRestClient.getStatsByPerformance(performanceIds);

    if (stats.isEmpty() || stats.get().byPerformance() == null) {
      return null;
    }

    Map<Long, Long> result = new HashMap<>();
    for (BookingStatsInfo.PerformanceStat stat : stats.get().byPerformance()) {
      if (stat != null && stat.performanceId() != null) {
        result.merge(stat.performanceId(), stat.confirmedRevenue(), Long::sum);
      }
    }
    return result;
  }

  private PerformanceAdminSummaryResponse toSummary(
      Performance performance,
      Map<Long, Long> revenueByPerformance,
      Map<Long, SeatCountsInfo> seatCounts) {

    SeatCountsInfo counts = seatCounts.get(performance.getId());
    Long soldSeats = (counts == null) ? null : counts.soldCount();
    Long totalSeats = (counts == null) ? null : counts.totalCount();
    Double occupancyRate = calculateRate(counts);
    Boolean soldOut =
        (counts == null || counts.totalCount() == 0)
            ? null
            : counts.soldCount() >= counts.totalCount();

    // 매출 맵이 null이면 조회 자체가 실패한 것이라 모든 공연이 null이다.
    // 맵은 있는데 키가 없으면 그 공연에 확정된 예매가 없다는 뜻이므로 0이다.
    Long revenue =
        (revenueByPerformance == null)
            ? null
            : revenueByPerformance.getOrDefault(performance.getId(), 0L);

    return new PerformanceAdminSummaryResponse(
        performance.getId(),
        performance.getTitle(),
        performance.getGenre(),
        performance.getGenre() == null ? null : performance.getGenre().getDescription(),
        performance.getShowDate(),
        performance.getShowTime(),
        performance.getPerformanceStatus(),
        soldSeats,
        totalSeats,
        occupancyRate,
        soldOut,
        revenue);
  }

  private Double calculateRate(SeatCountsInfo counts) {
    if (counts == null || counts.totalCount() == 0) {
      return null;
    }
    return BigDecimal.valueOf(counts.soldCount())
        .divide(BigDecimal.valueOf(counts.totalCount()), OCCUPANCY_SCALE, RoundingMode.HALF_UP)
        .doubleValue();
  }
}

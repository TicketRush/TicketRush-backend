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
import java.time.LocalDate;
import java.util.HashMap;
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
 * <p><b>알려진 비용: 페이지 요청 1회마다 원격 전건 집계가 2회 돈다.</b> 예매·좌석 어느 쪽도 공연 ID로 좁히는 축이 없어 매번 전체를 받아 와 현재 페이지에
 * 해당하는 공연만 꺼내 쓴다. 관리자가 페이지를 넘길 때마다 {@code booking}·{@code seat} 두 테이블에 전건 GROUP BY가 반복되는데, 그 둘은 오픈런
 * 트래픽을 직접 받는 쓰기 핫패스다.
 *
 * <p>이 이슈에서 감수한 이유는 관리자 동시 사용자가 소수이고 공연 수가 아직 작기 때문이며, <b>공연 수·예매 행 수가 늘면 성립하지 않는 전제</b>다. 해소하려면 예매
 * 집계 API에 공연 ID 필터를 열어 현재 페이지만 조회하도록 바꿔야 하는데, 그건 이 이슈에서 "단일 엔드포인트로 전량 반환"으로 정한 계약을 바꾸는 일이라 별도 이슈로
 * 분리한다. 그때까지의 완화 수단은 관리자 화면의 낮은 호출 빈도뿐이다.
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

    Map<Long, Long> revenueByPerformance = fetchRevenueByPerformance();
    Map<Long, SeatCountsInfo> seatCounts = seatRestClient.getSeatCounts();

    return page.map(performance -> toSummary(performance, revenueByPerformance, seatCounts));
  }

  /**
   * 공연별 매출을 가져온다.
   *
   * <p>기간을 <b>오늘 하루</b>로 넘기는 것은 목록이 일별 매출을 쓰지 않기 때문이다. 예매 집계 API는 요약·공연별·일별을 한 응답으로 주고 기간은 일별에만
   * 적용되므로, 여기서 넓은 기간을 넘기면 쓰지도 않을 행을 만들어 실어 보내게 된다. 공연별 매출은 어느 기간을 넘기든 전체 기간 값이다.
   *
   * <p><b>조회 실패는 {@code null}, 성공은 (비어 있더라도) 맵으로 갈린다.</b> 빈 맵으로 뭉개면 "확정된 예매가 없어 매출 0원"과 "매출을 못
   * 읽었다"가 응답에서 같은 모양이 된다 — 관리자가 구분할 수 없는 두 상태다.
   *
   * @return 공연 ID별 매출. 예매 서비스 조회에 실패하면 {@code null}
   */
  private Map<Long, Long> fetchRevenueByPerformance() {
    LocalDate today = LocalDate.now();
    Optional<BookingStatsInfo> stats = bookingRestClient.getStats(today, today);

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

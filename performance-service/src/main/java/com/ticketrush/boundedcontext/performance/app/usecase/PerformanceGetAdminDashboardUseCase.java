package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceAdminDashboardResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceAggregateRow;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.performance.out.apiclient.SeatRestClient;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.BookingStatsInfo;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.SeatCountsInfo;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관리자 대시보드 집계 (#563).
 *
 * <p><b>모수가 지표마다 다르다.</b> 하나로 통일하면 어느 한쪽이 거짓말을 한다.
 *
 * <ul>
 *   <li>등록 공연 수 — 삭제만 제외한 전체. 관리자가 "내가 등록한 공연이 몇 개인가"로 읽는 값이라 판매 전·취소도 센다.
 *   <li>평균 점유율 — 판매가 실제로 일어나는 공연(판매중·판매종료)만. 판매 전 공연을 넣으면 0%가 깔려 공연을 등록할수록 지표가 나빠 보인다.
 *   <li>매출·티켓 수 — 상태 무관 전체. 관리자 예매 통계 API와 같은 값이어야 두 화면이 갈리지 않는다.
 * </ul>
 *
 * <p><b>트랜잭션을 열지 않는다.</b> DB 조회는 한 번뿐이고 그 뒤가 원격 호출 둘이라, 트랜잭션으로 감싸면 예매·좌석 서비스의 응답을 기다리는 동안 DB 커넥션을
 * 붙잡는다. 읽기 한 번이라 트랜잭션으로 묶어 얻을 일관성도 없다.
 */
@Service
@RequiredArgsConstructor
public class PerformanceGetAdminDashboardUseCase {

  /** 기간 미지정 시 오늘을 포함한 최근 30일. */
  private static final int DEFAULT_PERIOD_DAYS = 30;

  /**
   * 일별 매출 조회 기간 상한(일). 응답 행 수와 예매 테이블 스캔량을 한 값으로 묶어 제한한다. 분기 단위 조회(약 92일)까지는 화면에서 자연스럽게 요구되므로 그 경계에
   * 맞췄다.
   */
  private static final int MAX_PERIOD_DAYS = 92;

  private static final int OCCUPANCY_SCALE = 4;

  private final PerformanceRepository performanceRepository;
  private final BookingRestClient bookingRestClient;
  private final SeatRestClient seatRestClient;

  public PerformanceAdminDashboardResponse execute(LocalDate from, LocalDate to) {
    LocalDate resolvedTo = (to == null) ? LocalDate.now() : to;
    LocalDate resolvedFrom = (from == null) ? resolvedTo.minusDays(DEFAULT_PERIOD_DAYS - 1L) : from;
    validatePeriod(resolvedFrom, resolvedTo);

    List<PerformanceAggregateRow> rows = performanceRepository.findAllAggregateRows();

    Optional<BookingStatsInfo> stats = bookingRestClient.getStats(resolvedFrom, resolvedTo);
    Map<Long, SeatCountsInfo> seatCounts = seatRestClient.getSeatCounts();

    Occupancy occupancy = calculateOccupancy(rows, seatCounts);

    // 요약이 비어 있으면 요약에서 파생되는 4필드를 조회 실패와 같게 다룬다. 200 + 정상 JSON인데 summary만
    // null인 경우(예: 예매 서비스가 필드명을 바꿈)는 RestClientException도, 파싱 실패도 아니라 여기까지 그대로
    // 도달한다. 그대로 역참조하면 fail-open이어야 할 경로가 NPE로 500이 된다.
    // 일별·장르별은 각자의 목록이 살아 있으면 그대로 채운다 — 축별로 독립해 저하되는 편이 "각 축의 필드만
    // 비운다"는 원칙에 맞다.
    BookingStatsInfo.Summary summary = stats.map(BookingStatsInfo::summary).orElse(null);

    return new PerformanceAdminDashboardResponse(
        rows.size(),
        (summary == null) ? null : summary.completedBookings(),
        (summary == null) ? null : summary.totalRevenue(),
        (summary == null) ? null : summary.revenueComplete(),
        (summary == null) ? null : summary.missingAmountBookings(),
        occupancy.rate(),
        occupancy.soldSeats(),
        occupancy.totalSeats(),
        stats.map(s -> toDailyRevenues(s.byDate())).orElse(null),
        stats.map(s -> toGenreRevenues(s.byPerformance(), rows)).orElse(null));
  }

  /**
   * 기간을 검증한다. 상한을 넘거나 뒤집힌 구간은 400으로 거부한다 — 조용히 잘라 주면 관리자가 요청한 것과 다른 기간의 매출을 보게 되는데, 화면에는 요청한 기간이
   * 그대로 남아 있어 그 차이를 알아챌 방법이 없다.
   */
  private void validatePeriod(LocalDate from, LocalDate to) {
    if (from.isAfter(to)) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_INVALID_DASHBOARD_PERIOD);
    }
    if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_DASHBOARD_PERIOD_TOO_LONG);
    }
  }

  /**
   * 가중 평균 점유율을 계산한다 — 공연별 점유율의 산술평균이 아니라 전체 판매 좌석 ÷ 전체 좌석이다. 산술평균은 20석짜리 소규모 공연 하나가 1,000석 공연과 같은
   * 무게를 가져 매출 규모와 방향이 어긋난다.
   *
   * <p><b>좌석 정보가 없는 공연은 분모에서도 빠진다.</b> 0석으로 채우면 좌석이 곧 생길 공연이 "영원히 매진 불가능한 공연"으로 평균을 끌어내린다. 좌석 생성은
   * 공연 등록 이벤트를 받아 비동기로 일어나므로 정상 운영 중에도 잠깐 발생하는 상태다.
   */
  private Occupancy calculateOccupancy(
      List<PerformanceAggregateRow> rows, Map<Long, SeatCountsInfo> seatCounts) {
    long soldSum = 0;
    long totalSum = 0;
    boolean anySeatData = false;

    for (PerformanceAggregateRow row : rows) {
      if (!isOccupancyTarget(row.status())) {
        continue;
      }
      SeatCountsInfo counts = seatCounts.get(row.id());
      if (counts == null) {
        continue;
      }
      anySeatData = true;
      soldSum += counts.soldCount();
      totalSum += counts.totalCount();
    }

    if (!anySeatData || totalSum == 0) {
      return new Occupancy(null, null, null);
    }

    double rate =
        BigDecimal.valueOf(soldSum)
            .divide(BigDecimal.valueOf(totalSum), OCCUPANCY_SCALE, RoundingMode.HALF_UP)
            .doubleValue();
    return new Occupancy(rate, soldSum, totalSum);
  }

  private boolean isOccupancyTarget(PerformanceStatus status) {
    return status == PerformanceStatus.ON_SALE || status == PerformanceStatus.CLOSED;
  }

  /**
   * 일별 매출을 응답 형태로 옮긴다.
   *
   * <p>{@code byDate}가 null이면 <b>빈 목록이 아니라 null</b>을 낸다. 빈 목록은 "그 기간에 매출이 하루도 없었다"는 사실이고, null은 "값을
   * 읽지 못했다"는 뜻이다 — 이 클래스가 다른 축에서 지키는 구분을 여기서만 깨뜨릴 이유가 없다. 반면 {@code toGenreRevenues}가 매출 없는 장르를 0으로
   * 채우는 것은 차트가 전체 장르 축을 그려야 하기 때문이라, 성격이 다르다.
   */
  private List<PerformanceAdminDashboardResponse.DailyRevenue> toDailyRevenues(
      List<BookingStatsInfo.DailyRevenue> byDate) {
    if (byDate == null) {
      return null;
    }
    List<PerformanceAdminDashboardResponse.DailyRevenue> result = new ArrayList<>(byDate.size());
    for (BookingStatsInfo.DailyRevenue row : byDate) {
      if (row != null && row.date() != null) {
        result.add(new PerformanceAdminDashboardResponse.DailyRevenue(row.date(), row.revenue()));
      }
    }
    return result;
  }

  /**
   * 공연별 매출을 장르로 접는다. 장르는 performance만 알고 매출은 booking만 아는 값이라, 이 결합은 어느 한쪽 DB에서는 만들 수 없다(ADR 0003).
   *
   * <p><b>매출이 0인 장르도 내린다.</b> 차트가 전체 장르 축을 그리려면 없는 장르가 아니라 0인 장르가 필요하다. 다만 그 근거는 <b>데이터를 읽었는데 그 장르에
   * 매출이 없는 경우</b>에만 해당한다 — 공연별 집계 자체를 못 읽었으면 {@code toDailyRevenues}와 같이 null을 낸다. 전 장르 0원으로 그리면
   * 관리자가 "이번 기간에 아무것도 안 팔렸다"로 읽는데, 그건 사실이 아니라 모르는 것이다.
   *
   * <p><b>삭제된 공연의 매출은 어느 장르에도 잡히지 않는다.</b> 삭제되면 장르를 알 수 없어서다. 그래서 장르별 합이 총 매출보다 작을 수 있다 — 총 매출은 예매
   * 기준이라 공연 삭제와 무관하게 남기 때문이다.
   */
  private List<PerformanceAdminDashboardResponse.GenreRevenue> toGenreRevenues(
      List<BookingStatsInfo.PerformanceStat> byPerformance, List<PerformanceAggregateRow> rows) {
    if (byPerformance == null) {
      return null;
    }

    Map<Long, Genre> genreById = new HashMap<>();
    for (PerformanceAggregateRow row : rows) {
      genreById.put(row.id(), row.genre());
    }

    Map<Genre, Long> revenueByGenre = new EnumMap<>(Genre.class);
    for (Genre genre : Genre.values()) {
      revenueByGenre.put(genre, 0L);
    }

    for (BookingStatsInfo.PerformanceStat stat : byPerformance) {
      if (stat == null || stat.performanceId() == null) {
        continue;
      }
      Genre genre = genreById.get(stat.performanceId());
      if (genre != null) {
        revenueByGenre.merge(genre, stat.confirmedRevenue(), Long::sum);
      }
    }

    List<PerformanceAdminDashboardResponse.GenreRevenue> result =
        new ArrayList<>(revenueByGenre.size());
    revenueByGenre.forEach(
        (genre, revenue) ->
            result.add(
                new PerformanceAdminDashboardResponse.GenreRevenue(
                    genre, genre.getDescription(), revenue)));
    return result;
  }

  /** 점유율 계산 결과. 셋 다 null이거나 셋 다 값이 있다 — 분모를 모르면 비율도 의미가 없다. */
  private record Occupancy(Double rate, Long soldSeats, Long totalSeats) {}
}

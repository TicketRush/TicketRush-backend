package com.ticketrush.boundedcontext.seat.out.repository;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapItemResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsByPerformanceResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

  @Query(
      "SELECT new com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse("
          + "COUNT(s), "
          + "COUNT(CASE WHEN s.seatStatus = :availableStatus "
          + "OR (s.seatStatus = :holdStatus AND s.holdExpiredAt <= :now) THEN 1 END), "
          + "COUNT(CASE WHEN s.seatStatus = :soldStatus THEN 1 END), "
          + "COUNT(CASE WHEN s.seatStatus = :holdStatus AND s.holdExpiredAt > :now THEN 1 END)) "
          + "FROM Seat s "
          + "WHERE s.performanceId = :performanceId")
  SeatStatusCountsResponse getStatusCountsByPerformanceIdAndStatuses(
      @Param("performanceId") Long performanceId,
      @Param("availableStatus") SeatStatus availableStatus,
      @Param("soldStatus") SeatStatus soldStatus,
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("now") LocalDateTime now);

  default SeatStatusCountsResponse getStatusCountsByPerformanceId(
      Long performanceId, LocalDateTime now) {
    return getStatusCountsByPerformanceIdAndStatuses(
        performanceId, SeatStatus.AVAILABLE, SeatStatus.SOLD, SeatStatus.HOLD, now);
  }

  /**
   * 전 공연 좌석 수를 한 번에 집계한다 (#563 관리자 대시보드).
   *
   * <p>세는 규칙은 {@link #getStatusCountsByPerformanceIdAndStatuses}와 <b>같은 식을 그대로 쓴다</b>. 규칙이 갈리면
   * 대시보드의 공연별 점유율과 좌석 현황 화면의 숫자가 어긋나는데, 둘 다 관리자가 같은 세션에서 보는 값이다.
   *
   * <p><b>WHERE가 없는 전건 GROUP BY다.</b> 좌석은 공연당 행 수가 고정적이고 관리자 대시보드는 호출 빈도가 극히 낮아, 이 조회를 위해 예매 폭주 경로가
   * 쓰는 {@code seat} 테이블에 인덱스를 얹지 않았다. 공연 수가 늘어 느려지면 그때 실측 근거를 갖고 추가한다. 관리자 공연 목록은 페이지마다 이걸 부르지 않도록
   * 공연 ID로 좁힌 {@link #getStatusCountsGroupedByPerformanceIdInAndStatuses}를 쓴다(#590).
   *
   * <p>{@code ORDER BY}를 명시하는 이유는 호출자가 공연 ID로 맵을 만들기 때문이 아니라, 정렬 없는 GROUP BY의 순서가 실행 계획에 따라 달라져
   * 테스트가 간헐 실패하는 것을 막기 위해서다.
   */
  @Query(
      "SELECT new com.ticketrush.boundedcontext.seat.app.dto.response"
          + ".SeatStatusCountsByPerformanceResponse("
          + "s.performanceId, "
          + "COUNT(s), "
          + "COUNT(CASE WHEN s.seatStatus = :availableStatus "
          + "OR (s.seatStatus = :holdStatus AND s.holdExpiredAt <= :now) THEN 1 END), "
          + "COUNT(CASE WHEN s.seatStatus = :soldStatus THEN 1 END), "
          + "COUNT(CASE WHEN s.seatStatus = :holdStatus AND s.holdExpiredAt > :now THEN 1 END)) "
          + "FROM Seat s "
          + "GROUP BY s.performanceId "
          + "ORDER BY s.performanceId ASC")
  List<SeatStatusCountsByPerformanceResponse> getStatusCountsGroupedByPerformanceAndStatuses(
      @Param("availableStatus") SeatStatus availableStatus,
      @Param("soldStatus") SeatStatus soldStatus,
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("now") LocalDateTime now);

  /**
   * 지정한 공연들의 좌석 수만 집계한다 (#590 관리자 공연 목록).
   *
   * <p>세는 규칙은 전건 버전과 <b>같은 식을 그대로 쓴다</b>. 한 화면의 두 경로가 다른 규칙으로 세면 안 된다.
   *
   * <p>실측(로컬 MySQL 8.0, seat 12,120행 / 공연 101건, 공연 50건 지정):
   *
   * <pre>
   * 전건: Group aggregate (rows=101)
   *         &lt;- Covering index scan on s using idx_seat_performance_id_status_hold_expired_at
   *            (cost=1251 rows=12272) -&gt; 12,120행 읽고 3.99ms
   * IN  : Group aggregate (rows=50) &lt;- Filter: performance_id in (...)
   *         &lt;- Covering index range scan on s using 같은 인덱스
   *            (cost=1205 rows=6000) -&gt; 6,000행 읽고 2.24ms
   * </pre>
   *
   * <b>읽는 행이 지정한 공연 몫으로 준다.</b> 위 회차에서 6,000행은 공연 101건 중 50건을 고른 결과이지 쿼리의 고정된 성질이 아니다 — 공연 수가 늘수록
   * 페이지가 차지하는 비율은 작아진다.
   *
   * <p><b>이 계획은 {@code idx_seat_performance_id_status_hold_expired_at}이 있을 때의 것이다.</b> 선두 컬럼이
   * {@code performance_id}라 IN이 range scan으로 좁혀지고 커버링이라 테이블을 건드리지 않는다. 이 조회를 위해 인덱스를 새로 얹지 않고도 그렇다
   * — 위 전건 버전의 판단(예매 폭주 경로에 인덱스를 더하지 않는다)이 그대로 유지된다. 다만 {@code @Index} 선언이 곧 배포 환경의 인덱스를 뜻하지는
   * 않으므로({@code Seat} 클래스 주석 참고) 운영에서 이 계획을 근거로 삼기 전에 {@code SHOW INDEX FROM seat}으로 먼저 확인해야 한다. 그
   * 인덱스가 없으면 아래 booking처럼 전건 스캔 뒤 필터로 degrade한다.
   */
  @Query(
      "SELECT new com.ticketrush.boundedcontext.seat.app.dto.response"
          + ".SeatStatusCountsByPerformanceResponse("
          + "s.performanceId, "
          + "COUNT(s), "
          + "COUNT(CASE WHEN s.seatStatus = :availableStatus "
          + "OR (s.seatStatus = :holdStatus AND s.holdExpiredAt <= :now) THEN 1 END), "
          + "COUNT(CASE WHEN s.seatStatus = :soldStatus THEN 1 END), "
          + "COUNT(CASE WHEN s.seatStatus = :holdStatus AND s.holdExpiredAt > :now THEN 1 END)) "
          + "FROM Seat s "
          + "WHERE s.performanceId IN :performanceIds "
          + "GROUP BY s.performanceId "
          + "ORDER BY s.performanceId ASC")
  List<SeatStatusCountsByPerformanceResponse> getStatusCountsGroupedByPerformanceIdInAndStatuses(
      @Param("performanceIds") List<Long> performanceIds,
      @Param("availableStatus") SeatStatus availableStatus,
      @Param("soldStatus") SeatStatus soldStatus,
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("now") LocalDateTime now);

  /**
   * {@code performanceIds}가 null이거나 비어 있으면 전 공연을 집계한다.
   *
   * <p>빈 목록을 그대로 IN에 넘기면 {@code IN ()}이 되어 JPQL이 성립하지 않으므로 여기서 갈라 낸다. 옵셔널 IN을 {@code (:ids IS NULL
   * OR ...)} 한 줄로 쓰지 않는 이유도 같다 — 컬렉션 파라미터는 IN 확장으로 바인딩되어 같은 이름을 스칼라 비교에 재사용할 수 없다.
   *
   * <p>HTTP 경로로는 빈 목록이 여기까지 오지 않는다. 컨트롤러의 {@code @Size(min = 1)}이 먼저 400으로 막기 때문이다. 이 가드는 서비스 내부에서
   * 직접 부르는 호출자를 위한 것이니 둘 중 하나를 중복으로 보고 지우지 않는다.
   */
  default List<SeatStatusCountsByPerformanceResponse> getStatusCountsGroupedByPerformance(
      LocalDateTime now, List<Long> performanceIds) {
    if (performanceIds == null || performanceIds.isEmpty()) {
      return getStatusCountsGroupedByPerformanceAndStatuses(
          SeatStatus.AVAILABLE, SeatStatus.SOLD, SeatStatus.HOLD, now);
    }

    return getStatusCountsGroupedByPerformanceIdInAndStatuses(
        performanceIds, SeatStatus.AVAILABLE, SeatStatus.SOLD, SeatStatus.HOLD, now);
  }

  @Query(
      "SELECT new com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapItemResponse("
          + "s.id, s.seatLayoutId, s.seatNumber, s.seatStatus, s.holdExpiredAt) "
          + "FROM Seat s "
          + "WHERE s.performanceId = :performanceId")
  List<SeatMapItemResponse> findSeatMapByPerformanceId(@Param("performanceId") Long performanceId);

  @Query(
      "SELECT new com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse("
          + "s.id, s.seatNumber) "
          + "FROM Seat s "
          + "WHERE s.id IN :seatIds")
  List<SeatNumberResponse> findSeatNumbersByIdIn(@Param("seatIds") List<Long> seatIds);

  /**
   * 관리자 API 전용 좌석 조회 (#562). {@code findById}를 쓰지 않는 이유는 경로의 {@code performanceId}가 장식이 되면 안 되기
   * 때문이다 — 임의의 공연 경로에 남의 공연 좌석 ID를 얹어도 조회·강제 해제가 되어버린다.
   */
  Optional<Seat> findByIdAndPerformanceId(Long id, Long performanceId);

  @Query(
      "SELECT s FROM Seat s "
          + "WHERE s.seatStatus = :holdStatus AND s.holdExpiredAt <= :now "
          + "ORDER BY s.id ASC")
  List<Seat> findExpiredHoldSeats(
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("now") LocalDateTime now,
      Pageable pageable);

  /*
   * 만료 HOLD 해제를 가드가 달린 조건부 UPDATE로 처리한다.
   *
   * 해제 경로(스케줄러 fallback, Redis 만료 리스너)는 좌석을 조회한 뒤 엔티티를 고쳐 더티 체킹으로 UPDATE하는데,
   * 그 UPDATE의 WHERE는 seat_id뿐이다. 조회와 flush 사이에 다른 트랜잭션이 끼어들면 남의 상태를 덮어쓴다. 조회
   * 시점의 상태 검사로는 막지 못한다. 그 검사도 결국 같은 조회 스냅샷을 보기 때문이다. 가드를 UPDATE 자체에 실어야
   * 원자적이다. 막아야 할 인터리브는 둘이다.
   *
   * 1. 결제 확정(confirmSoldById)이 먼저 커밋 -> 팔린 좌석(SOLD)을 AVAILABLE로 되돌려 재판매한다.
   *    -> seatStatus = HOLD 가드가 막는다.
   * 2. 다른 해제 경로가 먼저 커밋하고 그 좌석이 곧바로 재선점된다(ABA). 상태는 다시 HOLD라 상태 가드는 통과하지만,
   *    살아있는 남의 선점을 풀어버린다.
   *    -> holdExpiredAt 동등 비교가 막는다. 재선점은 만료 시각을 반드시 미래로 새로 쓰므로(Seat.hold가 과거
   *       시각을 거부한다) 조회 스냅샷의 만료 시각과 절대 같을 수 없다.
   *
   * 즉 holdExpiredAt = :holdExpiredAt은 "여전히 만료 상태인가"가 아니라 <b>"내가 본 그 선점 그대로인가"</b>를
   * 묻는다. bookingNumber를 함께 걸지 않는 이유도 이것이다. 방어력을 더하지 못하면서, booking_number가 NULL인
   * HOLD 좌석(#95 이전 hold(expiredAt)로 만들어진 행)을 영구히 해제 불가로 만든다 -- SQL에서 `= NULL`은 항상
   * UNKNOWN이라 매치되지 않는다. 그런 좌석은 조회에는 계속 잡히면서 해제만 안 되어, 페이지 0만 반복 조회하는
   * 청크 루프의 앞자리를 영원히 점유한다(뒤의 만료 좌석이 굶는다).
   *
   * holdExpiredAt은 HOLD 좌석이면 반드시 non-null이다(Seat.hold가 null 시각을 거부하고, 시각을 null로 되돌리는
   * releaseHold/releaseBooking은 동시에 HOLD를 벗어난다). 그래서 bookingNumber와 달리 `= NULL` 함정이 없다.
   *
   * <b>:holdExpiredAt에는 반드시 DB에서 읽어온 엔티티의 값을 넘긴다.</b> LocalDateTime.now()는 나노초지만
   * datetime(6) 컬럼은 마이크로초라 저장 시 절삭된다. 앱에서 만든 시각을 그대로 넘기면 동등 비교가 빗나가
   * 해제가 조용히 스킵된다.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE Seat s SET s.seatStatus = :availableStatus, s.holdExpiredAt = null, "
          + "s.bookingNumber = null "
          + "WHERE s.id = :seatId AND s.seatStatus = :holdStatus "
          + "AND s.holdExpiredAt = :holdExpiredAt")
  int releaseExpiredHoldById(
      @Param("seatId") Long seatId,
      @Param("holdExpiredAt") LocalDateTime holdExpiredAt,
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("availableStatus") SeatStatus availableStatus);

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE Seat s SET s.seatStatus = :soldStatus, s.holdExpiredAt = null "
          + "WHERE s.id = :seatId "
          + "AND s.bookingNumber = :bookingNumber "
          + "AND s.seatStatus = :holdStatus")
  int confirmSoldById(
      @Param("seatId") Long seatId,
      @Param("bookingNumber") String bookingNumber,
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("soldStatus") SeatStatus soldStatus);

  @Query("select count(s) from Seat s where s.seatStatus = :hold and s.holdExpiredAt > :now")
  long countHeldSeats(@Param("hold") SeatStatus hold, @Param("now") LocalDateTime now);

  /**
   * 만료됐지만 아직 해제되지 않은 HOLD 좌석 수 = 만료 fallback의 처리 적체.
   *
   * <p>비교 연산자는 {@link #findExpiredHoldSeats}와 같은 {@code <=}다. 게이지가 세는 집합과 스케줄러가 실제로 집어가는 집합이 어긋나면
   * 적체 곡선이 해제 진행을 따라가지 않는다. {@link #countHeldSeats}({@code > :now})와는 상호배타라 두 카운트의 합이 전체 HOLD 좌석
   * 수다.
   */
  @Query("select count(s) from Seat s where s.seatStatus = :hold and s.holdExpiredAt <= :now")
  long countExpiredHoldSeats(@Param("hold") SeatStatus hold, @Param("now") LocalDateTime now);
}

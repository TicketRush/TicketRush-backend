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
   * 쓰는 {@code seat} 테이블에 인덱스를 얹지 않았다. 공연 수가 늘어 느려지면 그때 실측 근거를 갖고 추가한다.
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

  default List<SeatStatusCountsByPerformanceResponse> getStatusCountsGroupedByPerformance(
      LocalDateTime now) {
    return getStatusCountsGroupedByPerformanceAndStatuses(
        SeatStatus.AVAILABLE, SeatStatus.SOLD, SeatStatus.HOLD, now);
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
